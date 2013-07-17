/*
 * Copyright (c) 2008-2010 by Jan Stender, Bjoern Kolbeck,
 *               Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.common;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.xtreemfs.common.config.ServiceConfig;
import org.xtreemfs.common.util.NetUtils;
import org.xtreemfs.common.uuids.ServiceUUID;
import org.xtreemfs.dir.DIRClient;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.pbrpc.Schemes;
import org.xtreemfs.foundation.pbrpc.client.PBRPCException;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.Auth;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.AuthType;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.POSIXErrno;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.AddressMapping;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.AddressMappingSet;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.Configuration;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.Service;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceDataMap;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceSet;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceType;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.KeyValuePair;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * A thread that regularly sends a heartbeat signal with fresh service data to the Directory Service.
 */
public class HeartbeatThread extends LifeCycleThread {

    /**
     * An interface that generates service data to be sent to the Directory Service. Each time a heartbeat
     * signal is sent, new service data will be generated by means of invoking <tt>getServiceData()</tt>.
     */
    public interface ServiceDataGenerator {

        public DIR.ServiceSet getServiceData();
    }

    public static final long      UPDATE_INTERVAL           = 60 * 1000;                                      // 60s

    public static final long      CONCURRENT_RETRY_INTERVAL = 5 * 1000;                     // 5s

    private final ServiceUUID           uuid;

    private final ServiceDataGenerator  serviceDataGen;

    private final DIRClient             client;

    private volatile boolean      quit;

    private final ServiceConfig   config;

    private final boolean         advertiseUDPEndpoints;

    private final String          proto;

    private String                advertisedHostName;

    private final UserCredentials uc;

    private static final String   STATIC_ATTR_PREFIX        = "static.";

    public static final String    STATUS_ATTR               = STATIC_ATTR_PREFIX + "status";

    /**
     * If set to true, a RegisterService call (which is the call used by this
     * thread to regularly report at the DIR) will not update the
     * last_updated_s field for the service.
     * Used by tools like xtfs_chstatus.
     */
    public static final String         DO_NOT_SET_LAST_UPDATED   = STATIC_ATTR_PREFIX + "do_not_set_last_updated";

    /**
     * Timestamp when the last heartbeat was sent.
     */
    private long                       lastHeartbeat;

    /** Guards pauseNumberOfWaitingThreads and paused. */
    private final Object               pauseLock;

    /** While >0, the thread will stop its periodic operations. */
    private int                        pauseNumberOfWaitingThreads;

    /** Set to true if the periodic operation is stopped. */
    private boolean                    paused;

    private static Auth                authNone;

    private volatile boolean           renewAddressMappings      = false;

    private Object                     updateIntervallMonitor    = new Object();

    static {
        authNone = Auth.newBuilder().setAuthType(AuthType.AUTH_NONE).build();
    }

    public HeartbeatThread(String name, DIRClient client, ServiceUUID uuid, ServiceDataGenerator serviceDataGen,
            ServiceConfig config, boolean advertiseUDPEndpoints) {

        super(name);

        setPriority(Thread.MAX_PRIORITY);

        this.pauseLock = new Object();

        this.client = client;
        this.uuid = uuid;
        this.serviceDataGen = serviceDataGen;
        this.config = config;
        this.advertiseUDPEndpoints = advertiseUDPEndpoints;
        this.uc = UserCredentials.newBuilder().setUsername("hb-thread").addGroups("xtreemfs-services")
                .build();
        if (!config.isUsingSSL()) {
            proto = Schemes.SCHEME_PBRPC;
        } else {
            if (config.isGRIDSSLmode()) {
                proto = Schemes.SCHEME_PBRPCG;
            } else {
                proto = Schemes.SCHEME_PBRPCS;
            }
        }

        if (config.isUsingMultihoming() && config.isUsingRenewalSignal()) {
            enableAddressRenewalSignal();
        }

        this.lastHeartbeat = TimeSync.getGlobalTime();
    }

    @Override
    public void shutdown() {
        try {
            if (client.clientIsAlive()) {
                client.xtreemfs_service_offline(null, authNone, uc, uuid.toString(), 1);
            }
        } catch (Exception ex) {
            Logging.logMessage(Logging.LEVEL_WARN, this, "could not set service offline at DIR");
            Logging.logError(Logging.LEVEL_WARN, this, ex);
        }

        this.quit = true;
        this.interrupt();
    }

    public void initialize() throws IOException {
        // initially, ...
        try {

            // ... for each UUID, ...
            for (;;) {
                // catch any ConcurrentModificationException and retry
                try {
                    registerServices(-1);
                    break;
                } catch (PBRPCException ex) {
                    if (ex.getPOSIXErrno() == POSIXErrno.POSIX_ERROR_EAGAIN) {
                        if (Logging.isInfo())
                            Logging.logMessage(Logging.LEVEL_INFO, Category.misc, this,
                                    "concurrent service registration; will try again after %d milliseconds",
                                    CONCURRENT_RETRY_INTERVAL);
                    } else
                        throw ex;
                }
            }

            // ... register the address mapping for the service
            registerAddressMappings();

        } catch (InterruptedException ex) {
        } catch (Exception ex) {
            Logging.logMessage(Logging.LEVEL_ERROR, this,
                    "an error occurred while initially contacting the Directory Service: " + ex);
            throw new IOException("cannot initialize service at XtreemFS DIR: " + ex, ex);
        }

        try {
            this.setServiceConfiguration();
        } catch (Exception e) {
            Logging.logMessage(Logging.LEVEL_ERROR, this,
                    "An error occurred while submitting the service configuration to the DIR service:");
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
    }

    @Override
    public void run() {
        try {

            notifyStarted();

            // periodically, ...
            while (!quit) {
                synchronized (pauseLock) {
                    while (pauseNumberOfWaitingThreads > 0) {
                        try {
                            pauseLock.wait();
                        } catch (InterruptedException ex) {
                            quit = true;
                            break;
                        }
                    }

                    paused = false;
                }

                try {
                    // update data on DIR; do not retry, as this is done periodically anyway
                    registerServices(1);
                } catch (PBRPCException ex) {
                    if (ex.getPOSIXErrno() == POSIXErrno.POSIX_ERROR_EAGAIN) {
                        if (Logging.isInfo())
                            Logging.logMessage(Logging.LEVEL_INFO, Category.misc, this,
                                    "concurrent service registration; will try again after %d milliseconds",
                                    UPDATE_INTERVAL);
                    } else
                        Logging.logMessage(Logging.LEVEL_ERROR, this,
                                "An error occurred during the periodic registration at the DIR:");
                    Logging.logError(Logging.LEVEL_ERROR, this, ex);
                } catch (IOException ex) {
                    Logging.logMessage(Logging.LEVEL_ERROR, this, "periodic registration at DIR failed: %s",
                            ex.toString());
                    if (Logging.isDebug())
                        Logging.logError(Logging.LEVEL_DEBUG, this, ex);
                } catch (InterruptedException ex) {
                    quit = true;
                    break;
                }
                
                
                if (renewAddressMappings) {
                    renewAddressMappings = false;
                    try {
                        registerAddressMappings();
                    } catch (IOException ex) {
                        Logging.logMessage(Logging.LEVEL_ERROR, this,
                                "requested renewal of address mappings failed: %s", ex.toString());
                        // If an error occurred, the renewal has to be rescheduled.
                        renewAddressMappings = true;
                    } catch (InterruptedException ex) {
                        quit = true;
                        break;
                    }
                }

                if (quit) {
                    break;
                }

                synchronized (pauseLock) {
                    paused = true;
                    pauseLock.notifyAll();
                }


                // If no renewal request is pending, this HeartbeatThread can wait for the next regular UPDATE_INTERVAL.
                if (!renewAddressMappings) {
                    try {
                        synchronized (updateIntervallMonitor) {
                            updateIntervallMonitor.wait(UPDATE_INTERVAL);
                        }
                    } catch (InterruptedException e) {
                        // ignore
                        // TODO(jdillmann): Ask why the interrupts above can terminate the thread and this one won't. If
                        // every InterruptedEx should make this Thread stop the run method could be restructured.
                    }

                }

            }

            notifyStopped();
        } catch (Throwable ex) {
            notifyCrashed(ex);
        }
    }

    private void registerServices(int numRetries) throws IOException, PBRPCException, InterruptedException {

        for (Service reg : serviceDataGen.getServiceData().getServicesList()) {
            // retrieve old DIR entry
            ServiceSet oldSet = numRetries == -1 ? client.xtreemfs_service_get_by_uuid(null, authNone, uc,
                    reg.getUuid()) : client.xtreemfs_service_get_by_uuid(null, authNone, uc, reg.getUuid(),
                    numRetries);
            long currentVersion = 0;
            Service oldService = oldSet.getServicesCount() == 0 ? null : oldSet.getServices(0);

            Map<String, String> staticAttrs = new HashMap();
            if (oldService != null) {
                currentVersion = oldService.getVersion();
                final ServiceDataMap data = oldService.getData();
                for (KeyValuePair pair : data.getDataList()) {
                    if (pair.getKey().startsWith(STATIC_ATTR_PREFIX))
                        staticAttrs.put(pair.getKey(), pair.getValue());
                }
            }

            if (!staticAttrs.containsKey(STATUS_ATTR))
                staticAttrs.put(STATUS_ATTR,
                        Integer.toString(DIR.ServiceStatus.SERVICE_STATUS_AVAIL.getNumber()));

            Service.Builder builder = reg.toBuilder();
            builder.setVersion(currentVersion);
            final ServiceDataMap.Builder data = ServiceDataMap.newBuilder();
            for (Entry<String, String> sAttr : staticAttrs.entrySet()) {
                data.addData(KeyValuePair.newBuilder().setKey(sAttr.getKey()).setValue(sAttr.getValue())
                        .build());
            }

            // If the service to register is a volume, and a volume with the
            // same ID but a different MRC has been registered already, it
            // may be necessary to register the volume's MRC as a replica.
            // In this case, all keys starting with 'mrc' have to be treated
            // separately.
            if (reg.getType() == ServiceType.SERVICE_TYPE_VOLUME && oldService != null
                    && oldService.getUuid().equals(reg.getUuid())) {

                // retrieve the MRC UUID attached to the volume to be
                // registered
                String mrcUUID = null;
                for (KeyValuePair kv : reg.getData().getDataList())
                    if (kv.getKey().equals("mrc")) {
                        mrcUUID = kv.getValue();
                        break;
                    }
                assert (mrcUUID != null);

                // check if the UUID is already contained in the volume's
                // list of MRCs and determine the next vacant key
                int maxMRCNo = 1;
                boolean contained = false;
                for (KeyValuePair kv : oldService.getData().getDataList()) {

                    if (kv.getKey().startsWith("mrc")) {

                        data.addData(kv);

                        if (kv.getValue().equals(mrcUUID))
                            contained = true;

                        if (!kv.getKey().equals("mrc")) {
                            int no = Integer.parseInt(kv.getKey().substring(3));
                            if (no > maxMRCNo)
                                maxMRCNo = no;
                        }
                    }
                }

                // if the UUID is not contained, add it
                if (!contained)
                    data.addData(KeyValuePair.newBuilder().setKey("mrc" + (maxMRCNo + 1)).setValue(mrcUUID));

                // add all other key-value pairs
                for (KeyValuePair kv : reg.getData().getDataList())
                    if (!kv.getKey().startsWith("mrc"))
                        data.addData(kv);

            }

            // in any other case, all data can be updated
            else
                data.addAllData(reg.getData().getDataList());

            builder.setData(data);
            if (numRetries == -1)
                client.xtreemfs_service_register(null, authNone, uc, builder.build());
            else
                client.xtreemfs_service_register(null, authNone, uc, builder.build(), numRetries);

            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.misc, this,
                        "%s successfully updated at Directory Service", uuid);
            }

            // update lastHeartbeat value
            this.lastHeartbeat = TimeSync.getGlobalTime();
        }
    }

    private void setServiceConfiguration() throws IOException, PBRPCException, InterruptedException {
        Configuration conf = client.xtreemfs_configuration_get(null, authNone, uc, uuid.toString());
        long currentVersion = 0;

        currentVersion = conf.getVersion();

        Configuration.Builder confBuilder = Configuration.newBuilder();
        confBuilder.setUuid(uuid.toString()).setVersion(currentVersion);
        for (Map.Entry<String, String> mapEntry : config.toHashMap().entrySet()) {
            confBuilder.addParameter(KeyValuePair.newBuilder().setKey(mapEntry.getKey())
                    .setValue(mapEntry.getValue()).build());
        }

        client.xtreemfs_configuration_set(null, authNone, uc, confBuilder.build());

        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.misc, this,
                    "%s successfully send configuration to Directory Service", uuid);
        }
    }

    private void registerAddressMappings() throws InterruptedException, IOException {
        List<AddressMapping.Builder> endpoints = null;

        // check if hostname or listen.address are set
        if ("".equals(config.getHostName()) && config.getAddress() == null) {

            endpoints = NetUtils.getReachableEndpoints(config.getPort(), proto);

            if (endpoints.size() > 0)
                advertisedHostName = endpoints.get(0).getAddress();

            if (advertiseUDPEndpoints) {
                endpoints.addAll(NetUtils.getReachableEndpoints(config.getPort(), Schemes.SCHEME_PBRPCU));
            }

            for (AddressMapping.Builder endpoint : endpoints) {
                endpoint.setUuid(uuid.toString());
            }

        } else {
            // if it is set, we should use that for UUID mapping!
            endpoints = new ArrayList(10);

            // remove the leading '/' if necessary
            String host = "".equals(config.getHostName()) ? config.getAddress().getHostName() : config.getHostName();
            if (host.startsWith("/")) {
                host = host.substring(1);
            }

            try {
                // see if we can resolve the hostname
                InetAddress ia = InetAddress.getByName(host);
            } catch (Exception ex) {
                Logging.logMessage(Logging.LEVEL_WARN, this, "WARNING! Could not resolve my "
                        + "hostname (%s) locally! Please make sure that the hostname is set correctly "
                        + "(either on your system or in the service config file). This will lead to "
                        + "problems if clients and other OSDs cannot resolve this service's address!\n", host);
            }

            AddressMapping.Builder tmp = AddressMapping.newBuilder().setUuid(uuid.toString()).setVersion(0)
                    .setProtocol(proto).setAddress(host).setPort(config.getPort()).setMatchNetwork("*").setTtlS(3600)
                    .setUri(proto + "://" + host + ":" + config.getPort());
            endpoints.add(tmp);
            // add an oncrpc/oncrpcs mapping

            /*
             * endpoints.add(new AddressMapping(uuid.toString(), 0, proto, host, config.getPort(), "*", 3600, proto +
             * "://" + host + ":" + config.getPort()));
             */

            advertisedHostName = host;

            if (advertiseUDPEndpoints) {
                /*
                 * endpoints.add(new AddressMapping(uuid.toString(), 0, XDRUtils.ONCRPCU_SCHEME, host, config.getPort(),
                 * "*", 3600, XDRUtils.ONCRPCU_SCHEME + "://" + host + ":" + config.getPort()));
                 */

                tmp = AddressMapping.newBuilder().setUuid(uuid.toString()).setVersion(0)
                        .setProtocol(Schemes.SCHEME_PBRPCU).setAddress(host).setPort(config.getPort())
                        .setMatchNetwork("*").setTtlS(3600)
                        .setUri(Schemes.SCHEME_PBRPCU + "://" + host + ":" + config.getPort());
                endpoints.add(tmp);
            }

        }

        if (Logging.isInfo()) {
            Logging.logMessage(Logging.LEVEL_INFO, Category.net, this,
                    "registering the following address mapping for the service:");
            for (AddressMapping.Builder mapping : endpoints) {
                Logging.logMessage(Logging.LEVEL_INFO, Category.net, this, "%s --> %s", mapping.getUuid(),
                        mapping.getUri());
            }
        }

        // fetch the latest address mapping version from the Directory Service
        long version = 0;
        AddressMappingSet ams = client.xtreemfs_address_mappings_get(null, authNone, uc, uuid.toString());

        // retrieve the version number from the address mapping
        if (ams.getMappingsCount() > 0) {
            version = ams.getMappings(0).getVersion();
        }

        if (endpoints.size() > 0) {
            endpoints.get(0).setVersion(version);
        }

        AddressMappingSet.Builder amsb = AddressMappingSet.newBuilder();
        for (AddressMapping.Builder mapping : endpoints) {
            amsb.addMappings(mapping);
        }
        // register/update the current address mapping
        client.xtreemfs_address_mappings_set(null, authNone, uc, amsb.build());
    }

    /**
     * Getter for the timestamp when the last heartbeat was sent.
     * 
     * @return long - timestamp like System.currentTimeMillis() returns it.
     */
    public long getLastHeartbeat() {
        return this.lastHeartbeat;
    }

    /**
     * @return the advertisedHostName
     */
    public String getAdvertisedHostName() {
        return advertisedHostName;
    }

    /**
     * Instructs the HeartbeatThread to pause its current operations. Blocks until it has done so.
     * 
     * @remark Do not forget to call {@link #resumeOperation()} afterward or the thread won't be unpaused.
     * 
     * @throws InterruptedException
     */
    public void pauseOperation() throws InterruptedException {
        synchronized (pauseLock) {
            pauseNumberOfWaitingThreads++;
            while (!paused) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    // In case of a shutdown, abort.
                    pauseNumberOfWaitingThreads--;
                    pauseLock.notifyAll();

                    throw e;
                }
            }
        }
    }

    /**
     * Tells the HeartbeatThread to resume operation.
     */
    public void resumeOperation() {
        synchronized (pauseLock) {
            pauseNumberOfWaitingThreads--;

            pauseLock.notifyAll();
        }
    }

    /**
     * Renew the address mappings immediately (HeartbeatThread will wake up when this is called).
     */
    public void renewAddressMappings() {
        renewAddressMappings = true;

        // To make the changes immediate, the thread has to be notified if it is sleeping.
        synchronized (updateIntervallMonitor) {
            updateIntervallMonitor.notifyAll();
        }
    }

    /**
     * Enable a signal handler for USR2 which will trigger the the address mapping renewal.
     * 
     * Since it is possible, that certain VMs are using the USR2 signal internally, the server should 
     * be started with the -XX:+UseAltSigs flag when signal usage is desired.
     * 
     * @return true if the signals could be enabled.
     */
    private boolean enableAddressRenewalSignal() {

        final HeartbeatThread hbt = this;

        // TODO(jdillmann): Test on different VMs and operating systems.
        try {
            Signal.handle(new Signal("USR2"), new SignalHandler() {

                @Override
                public void handle(Signal signal) {
                    // If the HeartbeatThread is still alive, renew the addresses and send them to the DIR.
                    if (hbt != null) {
                        hbt.renewAddressMappings();
                    }
                }
            });

        } catch (IllegalArgumentException e) {
            Logging.logMessage(Logging.LEVEL_WARN, this, "Could not register SignalHandler for USR2");
            return false;
        }

        return true;
    }
}
