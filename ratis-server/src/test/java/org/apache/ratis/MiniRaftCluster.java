/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.BlockRequestHandlingInjection;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.apache.ratis.server.impl.RaftServerProxy;
import org.apache.ratis.server.impl.RaftServerTestUtil;
import org.apache.ratis.server.storage.MemoryRaftLog;
import org.apache.ratis.server.storage.RaftLog;
import org.apache.ratis.shaded.proto.RaftProtos.ReplicationLevel;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.apache.ratis.server.impl.RaftServerConstants.DEFAULT_CALLID;
import static org.apache.ratis.server.impl.RaftServerConstants.DEFAULT_SEQNUM;

public abstract class MiniRaftCluster {
  public static final Logger LOG = LoggerFactory.getLogger(MiniRaftCluster.class);

  public static final String CLASS_NAME = MiniRaftCluster.class.getSimpleName();
  public static final String STATEMACHINE_CLASS_KEY = CLASS_NAME + ".statemachine.class";
  public static final Class<? extends StateMachine> STATEMACHINE_CLASS_DEFAULT = BaseStateMachine.class;

  public static abstract class Factory<CLUSTER extends MiniRaftCluster> {
    public interface Get<CLUSTER extends MiniRaftCluster> {
      Supplier<RaftProperties> properties = JavaUtils.memoize(() -> new RaftProperties());

      Factory<CLUSTER> getFactory();

      default RaftProperties getProperties() {
        return properties.get();
      }

      default CLUSTER newCluster(int numPeers) throws IOException {
        return getFactory().newCluster(numPeers, getProperties());
      }
    }

    public abstract CLUSTER newCluster(
        String[] ids, RaftProperties prop);

    public CLUSTER newCluster(int numServer, RaftProperties prop) {
      return newCluster(generateIds(numServer, 0), prop);
    }
  }

  public static abstract class RpcBase extends MiniRaftCluster {
    public RpcBase(String[] ids, RaftProperties properties, Parameters parameters) {
      super(ids, properties, parameters);
    }

    @Override
    public void setBlockRequestsFrom(String src, boolean block) {
      if (block) {
        BlockRequestHandlingInjection.getInstance().blockRequestor(src);
      } else {
        BlockRequestHandlingInjection.getInstance().unblockRequestor(src);
      }
    }

    public static int getPort(RaftPeerId id, RaftGroup group) {
      final List<RaftPeer> peers = group.getPeers().stream()
          .filter(raftPeer -> raftPeer.getId().equals(id)).collect(Collectors.toList());
      final String address = peers.isEmpty() ? null : peers.get(0).getAddress();
      final InetSocketAddress inetAddress = address != null?
          NetUtils.createSocketAddr(address): NetUtils.createLocalServerAddress();
      return inetAddress.getPort();
    }
  }

  public static class PeerChanges {
    public final RaftPeer[] allPeersInNewConf;
    public final RaftPeer[] newPeers;
    public final RaftPeer[] removedPeers;

    public PeerChanges(RaftPeer[] all, RaftPeer[] newPeers, RaftPeer[] removed) {
      this.allPeersInNewConf = all;
      this.newPeers = newPeers;
      this.removedPeers = removed;
    }
  }

  public static RaftGroup initRaftGroup(Collection<String> ids) {
    final RaftPeer[] peers = ids.stream()
        .map(RaftPeerId::valueOf)
        .map(id -> new RaftPeer(id, NetUtils.createLocalServerAddress()))
        .toArray(RaftPeer[]::new);
    return new RaftGroup(RaftGroupId.randomId(), peers);
  }

  private File getStorageDir(RaftPeerId id) {
    return new File(BaseTest.getRootTestDir()
        + "/" + getClass().getSimpleName() + "/" + id);
  }

  public static String[] generateIds(int numServers, int base) {
    String[] ids = new String[numServers];
    for (int i = 0; i < numServers; i++) {
      ids[i] = "s" + (i + base);
    }
    return ids;
  }

  protected RaftGroup group;
  protected final RaftProperties properties;
  protected final Parameters parameters;
  protected final Map<RaftPeerId, RaftServerProxy> servers = new ConcurrentHashMap<>();

  private final Timer timer;

  protected MiniRaftCluster(String[] ids, RaftProperties properties, Parameters parameters) {
    this.group = initRaftGroup(Arrays.asList(ids));
    LOG.info("new MiniRaftCluster {}", group);
    this.properties = new RaftProperties(properties);
    this.parameters = parameters;

    this.timer = JavaUtils.runRepeatedly(() -> LOG.info("TIMED-PRINT: " + printServers()),
        10, 10, TimeUnit.SECONDS);
    ExitUtils.disableSystemExit();
  }

  public RaftProperties getProperties() {
    return properties;
  }

  public MiniRaftCluster initServers() {
    LOG.info("servers = " + servers);
    if (servers.isEmpty()) {
      putNewServers(CollectionUtils.as(group.getPeers(), RaftPeer::getId), true);
    }
    return this;
  }

  private RaftServerProxy putNewServer(RaftPeerId id, boolean format) {
    return putNewServer(id, group, format);
  }

  public RaftServerProxy putNewServer(RaftPeerId id, RaftGroup group, boolean format) {
    final RaftServerProxy s = newRaftServer(id, group, format);
    Preconditions.assertTrue(servers.put(id, s) == null);
    return s;
  }

  private Collection<RaftServerProxy> putNewServers(
      Iterable<RaftPeerId> peers, boolean format) {
    return StreamSupport.stream(peers.spliterator(), false)
        .map(id -> putNewServer(id, format))
        .collect(Collectors.toList());
  }

  public void start() throws IOException {
    LOG.info(".............................................................. ");
    LOG.info("... ");
    LOG.info("...     Starting " + getClass().getSimpleName());
    LOG.info("... ");
    LOG.info(".............................................................. ");

    initServers();
    startServers(servers.values());
  }

  /**
   * start a stopped server again.
   */
  public void restartServer(RaftPeerId newId, boolean format) throws IOException {
    killServer(newId);
    servers.remove(newId);

    putNewServer(newId, format).start();
  }

  public void restart(boolean format) throws IOException {
    shutdown();

    List<RaftPeerId> idList = new ArrayList<>(servers.keySet());
    servers.clear();
    putNewServers(idList, format);
    start();
  }

  public int getMaxTimeout() {
    return RaftServerConfigKeys.Rpc.timeoutMax(properties).toInt(TimeUnit.MILLISECONDS);
  }

  private RaftServerProxy newRaftServer(RaftPeerId id, RaftGroup group, boolean format) {
    LOG.info("newRaftServer: {}, {}, format? {}", id, group, format);
    try {
      final File dir = getStorageDir(id);
      if (format) {
        FileUtils.deleteFully(dir);
        LOG.info("Formatted directory {}", dir);
      }
      final RaftProperties prop = new RaftProperties(properties);
      RaftServerConfigKeys.setStorageDir(prop, dir);
      final StateMachine stateMachine = getStateMachine4Test(properties);
      return newRaftServer(id, stateMachine, group, prop);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected abstract RaftServerProxy newRaftServer(
      RaftPeerId id, StateMachine stateMachine, RaftGroup group,
      RaftProperties properties) throws IOException;

  static StateMachine getStateMachine4Test(RaftProperties properties) {
    final Class<? extends StateMachine> smClass = properties.getClass(
        STATEMACHINE_CLASS_KEY,
        STATEMACHINE_CLASS_DEFAULT,
        StateMachine.class);

    final RuntimeException exception;
    try {
      return ReflectionUtils.newInstance(smClass);
    } catch(RuntimeException e) {
      exception = e;
    }

    try {
      final Class<?>[] argClasses = {RaftProperties.class};
      return ReflectionUtils.newInstance(smClass, argClasses, properties);
    } catch(RuntimeException e) {
      exception.addSuppressed(e);
    }
    throw exception;
  }

  public static List<RaftPeer> toRaftPeers(
      Collection<RaftServerProxy> servers) {
    return servers.stream()
        .map(MiniRaftCluster::toRaftPeer)
        .collect(Collectors.toList());
  }

  public static RaftPeer toRaftPeer(RaftServerImpl s) {
    return toRaftPeer(s.getProxy());
  }

  public static RaftPeer toRaftPeer(RaftServerProxy s) {
    return new RaftPeer(s.getId(), s.getServerRpc().getInetSocketAddress());
  }

  public PeerChanges addNewPeers(int number, boolean startNewPeer)
      throws IOException {
    return addNewPeers(generateIds(number, servers.size()), startNewPeer);
  }

  public PeerChanges addNewPeers(String[] ids, boolean startNewPeer) throws IOException {
    LOG.info("Add new peers {}", Arrays.asList(ids));

    // create and add new RaftServers
    final Collection<RaftServerProxy> newServers = putNewServers(
        CollectionUtils.as(Arrays.asList(ids), RaftPeerId::valueOf), true);

    startServers(newServers);
    if (!startNewPeer) {
      // start and then close, in order to bind the port
      newServers.forEach(p -> p.close());
    }

    final Collection<RaftPeer> newPeers = toRaftPeers(newServers);
    final RaftPeer[] np = newPeers.toArray(new RaftPeer[newPeers.size()]);
    newPeers.addAll(group.getPeers());
    RaftPeer[] p = newPeers.toArray(new RaftPeer[newPeers.size()]);
    group = new RaftGroup(group.getGroupId(), p);
    return new PeerChanges(p, np, new RaftPeer[0]);
  }

  static void startServers(Iterable<? extends RaftServer> servers) throws IOException {
    for(RaftServer s : servers) {
      s.start();
    }
  }

  /**
   * prepare the peer list when removing some peers from the conf
   */
  public PeerChanges removePeers(int number, boolean removeLeader,
      Collection<RaftPeer> excluded) {
    Collection<RaftPeer> peers = new ArrayList<>(group.getPeers());
    List<RaftPeer> removedPeers = new ArrayList<>(number);
    if (removeLeader) {
      final RaftPeer leader = toRaftPeer(getLeader());
      assert !excluded.contains(leader);
      peers.remove(leader);
      removedPeers.add(leader);
    }
    List<RaftServerImpl> followers = getFollowers();
    for (int i = 0, removed = 0; i < followers.size() &&
        removed < (removeLeader ? number - 1 : number); i++) {
      RaftPeer toRemove = toRaftPeer(followers.get(i));
      if (!excluded.contains(toRemove)) {
        peers.remove(toRemove);
        removedPeers.add(toRemove);
        removed++;
      }
    }
    RaftPeer[] p = peers.toArray(new RaftPeer[peers.size()]);
    group = new RaftGroup(group.getGroupId(), p);
    return new PeerChanges(p, new RaftPeer[0],
        removedPeers.toArray(new RaftPeer[removedPeers.size()]));
  }

  public void killServer(RaftPeerId id) {
    LOG.info("killServer " + id);
    servers.get(id).close();
  }

  public String printServers() {
    return printServers(null);
  }

  public String printServers(RaftGroupId groupId) {
    final StringBuilder b = new StringBuilder("printing ");
    if (groupId != null) {
      b.append(groupId);
    } else {
      b.append("ALL groups");
    }
    getRaftServerProxyStream(groupId).forEach(s -> b.append("\n  ").append(s));
    return b.toString();
  }

  public String printAllLogs() {
    StringBuilder b = new StringBuilder("\n#servers = " + servers.size() + "\n");
    for (RaftServerImpl s : iterateServerImpls()) {
      b.append("  ");
      b.append(s).append("\n");

      final RaftLog log = s.getState().getLog();
      if (log instanceof MemoryRaftLog) {
        b.append("    ");
        b.append(((MemoryRaftLog) log).getEntryString());
      }
    }
    return b.toString();
  }

  public RaftServerImpl getLeaderAndSendFirstMessage() throws IOException {
    return getLeaderAndSendFirstMessage(false);
  }

  public RaftServerImpl getLeaderAndSendFirstMessage(boolean ignoreException) throws IOException {
    final RaftServerImpl leader = getLeader();
    try(RaftClient client = createClient(leader.getId())) {
      client.send(new RaftTestUtil.SimpleMessage("first msg to make leader ready"));
    } catch (IOException e) {
      if (!ignoreException) {
        throw e;
      }
    }
    return leader;
  }

  public RaftServerImpl getLeader() {
    return getLeader((RaftGroupId)null);
  }

  public RaftServerImpl getLeader(RaftGroupId groupId) {
    return getLeader(getServerAliveStream(groupId));
  }

  static RaftServerImpl getLeader(Stream<RaftServerImpl> serverAliveStream) {
    final List<RaftServerImpl> leaders = new ArrayList<>();
    serverAliveStream.filter(RaftServerImpl::isLeader).forEach(s -> {
      if (leaders.isEmpty()) {
        leaders.add(s);
      } else {
        final long leaderTerm = leaders.get(0).getState().getCurrentTerm();
        final long term = s.getState().getCurrentTerm();
        if (term >= leaderTerm) {
          if (term > leaderTerm) {
            leaders.clear();
          }
          leaders.add(s);
        }
      }
    });
    if (leaders.isEmpty()) {
      return null;
    } else if (leaders.size() > 1) {
      throw new IllegalStateException(leaders
          + ", leaders.size() = " + leaders.size() + " > 1");
    }
    return leaders.get(0);
  }

  boolean isLeader(String leaderId) {
    final RaftServerImpl leader = getLeader();
    return leader != null && leader.getId().toString().equals(leaderId);
  }

  public List<RaftServerImpl> getFollowers() {
    return getServerAliveStream()
        .filter(RaftServerImpl::isFollower)
        .collect(Collectors.toList());
  }

  public Collection<RaftServerProxy> getServers() {
    return servers.values();
  }

  private Stream<RaftServerProxy> getRaftServerProxyStream(RaftGroupId groupId) {
    return getServers().stream()
        .filter(s -> groupId == null || s.containsGroup(groupId));
  }

  public Iterable<RaftServerImpl> iterateServerImpls() {
    return CollectionUtils.as(getServers(), this::getRaftServerImpl);
  }

  private Stream<RaftServerImpl> getServerStream(RaftGroupId groupId) {
    final Stream<RaftServerProxy> stream = getRaftServerProxyStream(groupId);
    return groupId != null? stream.map(s -> RaftServerTestUtil.getRaftServerImpl(s, groupId))
        : stream.flatMap(s -> RaftServerTestUtil.getRaftServerImpls(s).stream());
  }

  public Stream<RaftServerImpl> getServerAliveStream() {
    return getServerAliveStream(getGroupId());
  }

  private Stream<RaftServerImpl> getServerAliveStream(RaftGroupId groupId) {
    return getServerStream(groupId).filter(RaftServerImpl::isAlive);
  }

  public RaftServerProxy getServer(RaftPeerId id) {
    return servers.get(id);
  }

  public RaftServerImpl getRaftServerImpl(RaftPeerId id) {
    return getRaftServerImpl(servers.get(id));
  }

  private RaftServerImpl getRaftServerImpl(RaftServerProxy proxy) {
    return RaftServerTestUtil.getRaftServerImpl(proxy, getGroupId());
  }

  public List<RaftPeer> getPeers() {
    return toRaftPeers(getServers());
  }

  public RaftGroup getGroup() {
    return group;
  }

  public RaftClient createClient() {
    return createClient(null, group);
  }

  public RaftClient createClient(RaftGroup g) {
    return createClient(null, g);
  }

  public RaftClient createClientWithLeader() {
    return createClient(getLeader().getId(), group);
  }

  public RaftClient createClientWithFollower() {
    return createClient(getFollowers().get(0).getId(), group);
  }

  public RaftClient createClient(RaftPeerId leaderId) {
    return createClient(leaderId, group);
  }

  public RaftClient createClient(RaftPeerId leaderId, RaftGroup group) {
    return createClient(leaderId, group, null);
  }

  public RaftClient createClient(RaftPeerId leaderId, RaftGroup group, ClientId clientId) {
    return RaftClient.newBuilder()
        .setClientId(clientId)
        .setRaftGroup(group)
        .setLeaderId(leaderId)
        .setProperties(properties)
        .setParameters(parameters)
        .build();
  }

  public RaftClientRequest newRaftClientRequest(
      ClientId clientId, RaftPeerId leaderId, Message message) {
    return newRaftClientRequest(clientId, leaderId,
        DEFAULT_CALLID, DEFAULT_SEQNUM, message);
  }

  public RaftClientRequest newRaftClientRequest(
      ClientId clientId, RaftPeerId leaderId, long callId, long seqNum, Message message) {
    return newRaftClientRequest(clientId, leaderId, callId, seqNum, message, ReplicationLevel.MAJORITY);
  }

  public RaftClientRequest newRaftClientRequest(
      ClientId clientId, RaftPeerId leaderId, long callId, long seqNum, Message message, ReplicationLevel replication) {
    return new RaftClientRequest(clientId, leaderId, getGroupId(),
        callId, seqNum, message, RaftClientRequest.writeRequestType(replication));
  }

  public SetConfigurationRequest newSetConfigurationRequest(
      ClientId clientId, RaftPeerId leaderId,
      RaftPeer... peers) {
    return new SetConfigurationRequest(clientId, leaderId, getGroupId(),
        DEFAULT_CALLID, peers);
  }

  public void setConfiguration(RaftPeer... peers) throws IOException {
    try(RaftClient client = createClient()) {
      LOG.info("Start changing the configuration: {}", Arrays.asList(peers));
      final RaftClientReply reply = client.setConfiguration(peers);
      Preconditions.assertTrue(reply.isSuccess());
    }
  }

  public void shutdown() {
    LOG.info("************************************************************** ");
    LOG.info("*** ");
    LOG.info("***     Stopping " + getClass().getSimpleName());
    LOG.info("*** ");
    LOG.info("************************************************************** ");
    LOG.info(printServers());

    final ExecutorService executor = Executors.newFixedThreadPool(servers.size());
    try {
      getServers().forEach(proxy -> executor.submit(proxy::close));
      // just wait for a few seconds
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch(InterruptedException e) {
      LOG.warn("shutdown interrupted", e);
    } finally {
      executor.shutdownNow();
    }

    timer.cancel();
    ExitUtils.assertNotTerminated();
  }

  /**
   * Block all the incoming requests for the peer with leaderId. Also delay
   * outgoing or incoming msg for all other peers.
   */
  protected abstract void blockQueueAndSetDelay(String leaderId, int delayMs)
      throws InterruptedException;

  /**
   * Try to enforce the leader of the cluster.
   * @param leaderId ID of the targeted leader server.
   * @return true if server has been successfully enforced to the leader, false
   *         otherwise.
   */
  public boolean tryEnforceLeader(String leaderId) throws InterruptedException {
    // do nothing and see if the given id is already a leader.
    if (isLeader(leaderId)) {
      return true;
    }

    // Blocking all other server's RPC read process to make sure a read takes at
    // least ELECTION_TIMEOUT_MIN. In this way when the target leader request a
    // vote, all non-leader servers can grant the vote.
    // Disable the target leader server RPC so that it can request a vote.
    blockQueueAndSetDelay(leaderId,
        RaftServerConfigKeys.Rpc.TIMEOUT_MIN_DEFAULT.toInt(TimeUnit.MILLISECONDS));

    // Reopen queues so that the vote can make progress.
    blockQueueAndSetDelay(leaderId, 0);

    return isLeader(leaderId);
  }

  /** Block/unblock the requests sent from the given source. */
  public abstract void setBlockRequestsFrom(String src, boolean block);

  public RaftGroupId getGroupId() {
    return group.getGroupId();
  }
}
