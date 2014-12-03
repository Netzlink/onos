/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.openflow.controller.impl;

import static org.onlab.util.Tools.namedThreads;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.Lists;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.openflow.controller.DefaultOpenFlowPacketContext;
import org.onosproject.openflow.controller.Dpid;
import org.onosproject.openflow.controller.OpenFlowController;
import org.onosproject.openflow.controller.OpenFlowEventListener;
import org.onosproject.openflow.controller.OpenFlowPacketContext;
import org.onosproject.openflow.controller.OpenFlowSwitch;
import org.onosproject.openflow.controller.OpenFlowSwitchListener;
import org.onosproject.openflow.controller.PacketListener;
import org.onosproject.openflow.controller.RoleState;
import org.onosproject.openflow.controller.driver.OpenFlowAgent;
import org.projectfloodlight.openflow.protocol.OFCircuitPortStatus;
import org.projectfloodlight.openflow.protocol.OFExperimenter;
import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFPortStatus;
import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsReplyFlags;
import org.projectfloodlight.openflow.protocol.OFStatsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

@Component(immediate = true)
@Service
public class OpenFlowControllerImpl implements OpenFlowController {

    private static final Logger log =
            LoggerFactory.getLogger(OpenFlowControllerImpl.class);

    private final ExecutorService executor = Executors.newFixedThreadPool(16,
            namedThreads("of-event-%d"));

    protected ConcurrentHashMap<Dpid, OpenFlowSwitch> connectedSwitches =
            new ConcurrentHashMap<Dpid, OpenFlowSwitch>();
    protected ConcurrentHashMap<Dpid, OpenFlowSwitch> activeMasterSwitches =
            new ConcurrentHashMap<Dpid, OpenFlowSwitch>();
    protected ConcurrentHashMap<Dpid, OpenFlowSwitch> activeEqualSwitches =
            new ConcurrentHashMap<Dpid, OpenFlowSwitch>();

    protected OpenFlowSwitchAgent agent = new OpenFlowSwitchAgent();
    protected Set<OpenFlowSwitchListener> ofSwitchListener = new HashSet<>();

    protected Multimap<Integer, PacketListener> ofPacketListener =
            ArrayListMultimap.create();

    protected Set<OpenFlowEventListener> ofEventListener = Sets.newHashSet();

    protected Multimap<Dpid, OFFlowStatsEntry> fullStats =
            ArrayListMultimap.create();

    private final Controller ctrl = new Controller();

    @Activate
    public void activate() {
        ctrl.start(agent);
    }

    @Deactivate
    public void deactivate() {
        ctrl.stop();
    }

    @Override
    public Iterable<OpenFlowSwitch> getSwitches() {
        return connectedSwitches.values();
    }

    @Override
    public Iterable<OpenFlowSwitch> getMasterSwitches() {
        return activeMasterSwitches.values();
    }

    @Override
    public Iterable<OpenFlowSwitch> getEqualSwitches() {
        return activeEqualSwitches.values();
    }

    @Override
    public OpenFlowSwitch getSwitch(Dpid dpid) {
        return connectedSwitches.get(dpid);
    }

    @Override
    public OpenFlowSwitch getMasterSwitch(Dpid dpid) {
        return activeMasterSwitches.get(dpid);
    }

    @Override
    public OpenFlowSwitch getEqualSwitch(Dpid dpid) {
        return activeEqualSwitches.get(dpid);
    }

    @Override
    public void addListener(OpenFlowSwitchListener listener) {
        if (!ofSwitchListener.contains(listener)) {
            this.ofSwitchListener.add(listener);
        }
    }

    @Override
    public void removeListener(OpenFlowSwitchListener listener) {
        this.ofSwitchListener.remove(listener);
    }

    @Override
    public void addPacketListener(int priority, PacketListener listener) {
        ofPacketListener.put(priority, listener);
    }

    @Override
    public void removePacketListener(PacketListener listener) {
        ofPacketListener.values().remove(listener);
    }

    @Override
    public void addEventListener(OpenFlowEventListener listener) {
        ofEventListener.add(listener);
    }

    @Override
    public void removeEventListener(OpenFlowEventListener listener) {
        ofEventListener.remove(listener);
    }

    @Override
    public void write(Dpid dpid, OFMessage msg) {
        this.getSwitch(dpid).sendMsg(msg);
    }

    @Override
    public void processPacket(Dpid dpid, OFMessage msg) {
        Collection<OFFlowStatsEntry> stats;
        switch (msg.getType()) {
        case PORT_STATUS:
            for (OpenFlowSwitchListener l : ofSwitchListener) {
                l.portChanged(dpid, (OFPortStatus) msg);
            }
            break;
        case FEATURES_REPLY:
            for (OpenFlowSwitchListener l : ofSwitchListener) {
                l.switchChanged(dpid);
            }
            break;
        case PACKET_IN:
            OpenFlowPacketContext pktCtx = DefaultOpenFlowPacketContext
            .packetContextFromPacketIn(this.getSwitch(dpid),
                    (OFPacketIn) msg);
            for (PacketListener p : ofPacketListener.values()) {
                p.handlePacket(pktCtx);
            }
            break;
        case STATS_REPLY:
            OFStatsReply reply = (OFStatsReply) msg;
            if (reply.getStatsType().equals(OFStatsType.PORT_DESC)) {
                for (OpenFlowSwitchListener l : ofSwitchListener) {
                    l.switchChanged(dpid);
                }
            }
            stats = publishStats(dpid, reply);
            if (stats != null) {
                OFFlowStatsReply.Builder rep =
                        OFFactories.getFactory(msg.getVersion()).buildFlowStatsReply();
                rep.setEntries(Lists.newLinkedList(stats));
                executor.submit(new OFMessageHandler(dpid, rep.build()));
            }
            break;

        case FLOW_REMOVED:
        case ERROR:
        case BARRIER_REPLY:
            executor.submit(new OFMessageHandler(dpid, msg));
            break;
        case EXPERIMENTER:
            // Handle optical port stats
            if (((OFExperimenter) msg).getExperimenter() == 0x748771) {
                OFCircuitPortStatus circuitPortStatus = (OFCircuitPortStatus) msg;
                OFPortStatus.Builder portStatus = this.getSwitch(dpid).factory().buildPortStatus();
                OFPortDesc.Builder portDesc = this.getSwitch(dpid).factory().buildPortDesc();
                portDesc.setPortNo(circuitPortStatus.getPortNo())
                        .setHwAddr(circuitPortStatus.getHwAddr())
                        .setName(circuitPortStatus.getName())
                        .setConfig(circuitPortStatus.getConfig())
                        .setState(circuitPortStatus.getState());
                portStatus.setReason(circuitPortStatus.getReason()).setDesc(portDesc.build());
                for (OpenFlowSwitchListener l : ofSwitchListener) {
                    l.portChanged(dpid, portStatus.build());
                }
            } else {
                log.warn("Handling experimenter type {} not yet implemented",
                        ((OFExperimenter) msg).getExperimenter(), msg);
            }
            break;
        default:
            log.warn("Handling message type {} not yet implemented {}",
                    msg.getType(), msg);
        }
    }

    private synchronized Collection<OFFlowStatsEntry> publishStats(Dpid dpid,
                                                                   OFStatsReply reply) {
        //TODO: Get rid of synchronized
        if (reply.getStatsType() != OFStatsType.FLOW) {
            return null;
        }
        final OFFlowStatsReply replies = (OFFlowStatsReply) reply;
        fullStats.putAll(dpid, replies.getEntries());
        if (!reply.getFlags().contains(OFStatsReplyFlags.REPLY_MORE)) {
            return fullStats.removeAll(dpid);
        }
        return null;
    }

    @Override
    public void setRole(Dpid dpid, RoleState role) {
        final OpenFlowSwitch sw = getSwitch(dpid);
        if (sw == null) {
            log.debug("Switch not connected. Ignoring setRole({}, {})", dpid, role);
            return;
        }
        sw.setRole(role);
    }

    /**
     * Implementation of an OpenFlow Agent which is responsible for
     * keeping track of connected switches and the state in which
     * they are.
     */
    public class OpenFlowSwitchAgent implements OpenFlowAgent {

        private final Logger log = LoggerFactory.getLogger(OpenFlowSwitchAgent.class);
        private final Lock switchLock = new ReentrantLock();

        @Override
        public boolean addConnectedSwitch(Dpid dpid, OpenFlowSwitch sw) {

            if (connectedSwitches.get(dpid) != null) {
                log.error("Trying to add connectedSwitch but found a previous "
                        + "value for dpid: {}", dpid);
                return false;
            } else {
                log.info("Added switch {}", dpid);
                connectedSwitches.put(dpid, sw);
                for (OpenFlowSwitchListener l : ofSwitchListener) {
                    l.switchAdded(dpid);
                }
                return true;
            }
        }

        @Override
        public boolean validActivation(Dpid dpid) {
            if (connectedSwitches.get(dpid) == null) {
                log.error("Trying to activate switch but is not in "
                        + "connected switches: dpid {}. Aborting ..",
                        dpid);
                return false;
            }
            if (activeMasterSwitches.get(dpid) != null ||
                    activeEqualSwitches.get(dpid) != null) {
                log.error("Trying to activate switch but it is already "
                        + "activated: dpid {}. Found in activeMaster: {} "
                        + "Found in activeEqual: {}. Aborting ..", new Object[]{
                                dpid,
                                (activeMasterSwitches.get(dpid) == null) ? 'N' : 'Y',
                                        (activeEqualSwitches.get(dpid) == null) ? 'N' : 'Y'});
                return false;
            }
            return true;
        }


        @Override
        public boolean addActivatedMasterSwitch(Dpid dpid, OpenFlowSwitch sw) {
            switchLock.lock();
            try {
                if (!validActivation(dpid)) {
                    return false;
                }
                activeMasterSwitches.put(dpid, sw);
                return true;
            } finally {
                switchLock.unlock();
            }
        }

        @Override
        public boolean addActivatedEqualSwitch(Dpid dpid, OpenFlowSwitch sw) {
            switchLock.lock();
            try {
                if (!validActivation(dpid)) {
                    return false;
                }
                activeEqualSwitches.put(dpid, sw);
                log.info("Added Activated EQUAL Switch {}", dpid);
                return true;
            } finally {
                switchLock.unlock();
            }
        }

        @Override
        public void transitionToMasterSwitch(Dpid dpid) {
            switchLock.lock();
            try {
                if (activeMasterSwitches.containsKey(dpid)) {
                    return;
                }
                OpenFlowSwitch sw = activeEqualSwitches.remove(dpid);
                if (sw == null) {
                    sw = getSwitch(dpid);
                    if (sw == null) {
                        log.error("Transition to master called on sw {}, but switch "
                                + "was not found in controller-cache", dpid);
                        return;
                    }
                }
                log.info("Transitioned switch {} to MASTER", dpid);
                activeMasterSwitches.put(dpid, sw);
            } finally {
                switchLock.unlock();
            }
        }


        @Override
        public void transitionToEqualSwitch(Dpid dpid) {
            switchLock.lock();
            try {
                if (activeEqualSwitches.containsKey(dpid)) {
                    return;
                }
                OpenFlowSwitch sw = activeMasterSwitches.remove(dpid);
                if (sw == null) {
                    sw = getSwitch(dpid);
                    if (sw == null) {
                        log.error("Transition to equal called on sw {}, but switch "
                                + "was not found in controller-cache", dpid);
                        return;
                    }
                }
                log.info("Transitioned switch {} to EQUAL", dpid);
                activeEqualSwitches.put(dpid, sw);
            } finally {
                switchLock.unlock();
            }

        }

        @Override
        public void removeConnectedSwitch(Dpid dpid) {
            connectedSwitches.remove(dpid);
            OpenFlowSwitch sw = activeMasterSwitches.remove(dpid);
            if (sw == null) {
                log.warn("sw was null for {}", dpid);
                sw = activeEqualSwitches.remove(dpid);
            }
            for (OpenFlowSwitchListener l : ofSwitchListener) {
                log.warn("removal for {}", dpid);
                l.switchRemoved(dpid);
            }
        }

        @Override
        public void processMessage(Dpid dpid, OFMessage m) {
            processPacket(dpid, m);
        }

        @Override
        public void returnRoleReply(Dpid dpid, RoleState requested, RoleState response) {
            for (OpenFlowSwitchListener l : ofSwitchListener) {
                l.receivedRoleReply(dpid, requested, response);
            }
        }
    }

    private final class OFMessageHandler implements Runnable {

        private final OFMessage msg;
        private final Dpid dpid;

        public OFMessageHandler(Dpid dpid, OFMessage msg) {
            this.msg = msg;
            this.dpid = dpid;
        }

        @Override
        public void run() {
            for (OpenFlowEventListener listener : ofEventListener) {
                listener.handleMessage(dpid, msg);
            }
        }

    }

}
