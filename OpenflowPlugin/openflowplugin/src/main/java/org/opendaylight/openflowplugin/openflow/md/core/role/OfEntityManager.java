/**
 * Copyright (c) 2013, 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.openflowplugin.openflow.md.core.role;

import java.math.BigInteger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.opendaylight.controller.md.sal.common.api.clustering.CandidateAlreadyRegisteredException;
import com.google.common.base.Optional;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.common.api.data.AsyncTransaction;
import org.opendaylight.controller.md.sal.common.api.data.TransactionChain;
import org.opendaylight.controller.md.sal.common.api.data.TransactionChainListener;
import org.opendaylight.controller.md.sal.common.api.clustering.Entity;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipService;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipState;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipCandidateRegistration;
import org.opendaylight.controller.sal.binding.api.RpcProviderRegistry;
import org.opendaylight.openflowplugin.api.openflow.md.ModelDrivenSwitch;
import org.opendaylight.openflowplugin.api.openflow.md.core.NotificationQueueWrapper;
import org.opendaylight.yangtools.concepts.CompositeObjectRegistration;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.common.types.rev130731.ControllerRole;
import org.opendaylight.openflowplugin.api.openflow.md.core.session.SessionContext;
import org.opendaylight.controller.md.sal.common.api.clustering.EntityOwnershipChange;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.openflow.common.config.impl.rev140326.OfpRole;
import org.opendaylight.openflowplugin.openflow.md.core.session.RolePushTask;
import org.opendaylight.openflowplugin.openflow.md.core.session.RolePushException;
import org.opendaylight.openflowplugin.openflow.md.util.RoleUtil;
import org.opendaylight.openflowplugin.openflow.md.core.ThreadPoolLoggingExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureCallback;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.CheckedFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OfEntityManager implements TransactionChainListener{
    private static final Logger LOG = LoggerFactory.getLogger(OfEntityManager.class);

    private static final QName ENTITY_QNAME =
        org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.md.sal.core.general.entity.rev150820.Entity.QNAME;
    private static final QName ENTITY_NAME = QName.create(ENTITY_QNAME, "name");

    private DataBroker dataBroker;
    private EntityOwnershipService entityOwnershipService;
    private final OpenflowOwnershipListener ownershipListener;
    private final AtomicBoolean registeredListener = new AtomicBoolean();
    private ConcurrentHashMap<Entity, MDSwitchMetaData> entsession;
    private ConcurrentHashMap<Entity, EntityOwnershipCandidateRegistration> entRegistrationMap;
    private final String DEVICE_TYPE = "openflow";

    private final ListeningExecutorService pool;

    public OfEntityManager( EntityOwnershipService entityOwnershipService ) {
        this.entityOwnershipService = entityOwnershipService;
        ownershipListener = new OpenflowOwnershipListener(this);
        entsession = new ConcurrentHashMap<>();
        entRegistrationMap = new ConcurrentHashMap<>();
        ThreadPoolLoggingExecutor delegate = new ThreadPoolLoggingExecutor(
            20, 20, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), "ofEntity");
        pool =  MoreExecutors.listeningDecorator(delegate);
    }

    public void setDataBroker(DataBroker dbBroker) {
        this.dataBroker = dbBroker;
    }

    public void requestOpenflowEntityOwnership(final ModelDrivenSwitch ofSwitch,
                                               final SessionContext context,
                                               final NotificationQueueWrapper wrappedNotification,
                                               final RpcProviderRegistry rpcProviderRegistry) {
        MDSwitchMetaData entityMetaData =
                new MDSwitchMetaData(ofSwitch,context,wrappedNotification,rpcProviderRegistry);

        if (registeredListener.compareAndSet(false, true)) {
            entityOwnershipService.registerListener(DEVICE_TYPE, ownershipListener);
        }
        final Entity entity = new Entity(DEVICE_TYPE, ofSwitch.getNodeId().getValue());
        entsession.put(entity, entityMetaData);

        //Register as soon as possible to avoid missing any entity ownership change event
        final EntityOwnershipCandidateRegistration entityRegistration;
        try {
            entityRegistration = entityOwnershipService.registerCandidate(entity);
            entRegistrationMap.put(entity, entityRegistration);
            LOG.info("requestOpenflowEntityOwnership: Registered controller for the ownership of {}", ofSwitch.getNodeId() );
        } catch (CandidateAlreadyRegisteredException e) {
            // we can log and move for this error, as listener is present and role changes will be served.
            LOG.error("requestOpenflowEntityOwnership : Controller registration for ownership of {} failed ", ofSwitch.getNodeId(), e );
        }

        Optional <EntityOwnershipState> entityOwnershipStateOptional =
                entityOwnershipService.getOwnershipState(entity);

        if (entityOwnershipStateOptional.isPresent()) {
            final EntityOwnershipState entityOwnershipState = entityOwnershipStateOptional.get();
            if (entityOwnershipState.hasOwner()) {
                final OfpRole newRole ;
                if (entityOwnershipState.isOwner()) {
                    LOG.info("requestOpenflowEntityOwnership: Set controller as a MASTER controller " +
                            "because it's the OWNER of the {}", ofSwitch.getNodeId());
                    newRole =  OfpRole.BECOMEMASTER;
                    setDeviceOwnershipState(entity,true);
                    registerRoutedRPCForSwitch(entsession.get(entity));
                } else {
                    LOG.info("requestOpenflowEntityOwnership: Set controller as a SLAVE controller " +
                            "because it's is not the owner of the {}", ofSwitch.getNodeId());
                    newRole = OfpRole.BECOMESLAVE;
                    setDeviceOwnershipState(entity,false);
                }
                RolePushTask task = new RolePushTask(newRole, context);
                ListenableFuture<Boolean> rolePushResult = pool.submit(task);
                CheckedFuture<Boolean, RolePushException> rolePushResultChecked =
                    RoleUtil.makeCheckedRuleRequestFxResult(rolePushResult);
                Futures.addCallback(rolePushResult, new FutureCallback<Boolean>(){
                    @Override
                    public void onSuccess(Boolean result){
                        LOG.info("requestOpenflowEntityOwnership: Controller is now {} of the {}",
                                newRole == OfpRole.BECOMEMASTER?"MASTER":"SLAVE",ofSwitch.getNodeId() );

                        sendNodeAddedNotification(entsession.get(entity));
                    }
                    @Override
                    public void onFailure(Throwable t){
                        LOG.warn("requestOpenflowEntityOwnership: Controller is not able to set " +
                                "the role for {}",ofSwitch.getNodeId(), t);

                        if(newRole == OfpRole.BECOMEMASTER) {
                            LOG.info("requestOpenflowEntityOwnership: ..and controller is the owner of the " +
                                    "device {}. Closing the registration, so other controllers can try to " +
                                    "become owner and attempt to be master controller.",ofSwitch.getNodeId());

                            EntityOwnershipCandidateRegistration ownershipRegistrent = entRegistrationMap.get(entity);
                            if (ownershipRegistrent != null) {
                                ownershipRegistrent.close();
                                entRegistrationMap.remove(entity);
                            }

                            LOG.info("requestOpenflowEntityOwnership: ..and registering it back to participate" +
                                    " in ownership of the entity.");

                            EntityOwnershipCandidateRegistration entityRegistration;
                            try {
                                entityRegistration = entityOwnershipService.registerCandidate(entity);
                                entRegistrationMap.put(entity, entityRegistration);
                                LOG.info("requestOpenflowEntityOwnership: re-registered controller for " +
                                        "ownership of the {}", ofSwitch.getNodeId() );
                            } catch (CandidateAlreadyRegisteredException e) {
                                // we can log and move for this error, as listener is present and role changes will be served.
                                LOG.error("requestOpenflowEntityOwnership: *Surprisingly* Entity is already " +
                                        "registered with EntityOwnershipService : {}", ofSwitch.getNodeId(), e );
                            }

                        } else {
                                LOG.error("requestOpenflowEntityOwnership : Not able to set role {} for {}"
                                        , newRole == OfpRole.BECOMEMASTER?"MASTER":"SLAVE", ofSwitch.getNodeId());
                        }
                    }
                 });
             }
         }
    }

    public void setSlaveRole(SessionContext sessionContext) {
        OfpRole newRole = OfpRole.BECOMESLAVE;
        if (sessionContext != null) {
            final BigInteger targetSwitchDPId = sessionContext.getFeatures().getDatapathId();
            LOG.debug("setSlaveRole: Set controller as a SLAVE controller for {}", targetSwitchDPId.toString());

            RolePushTask task = new RolePushTask(newRole, sessionContext);
            ListenableFuture<Boolean> rolePushResult = pool.submit(task);
            final CheckedFuture<Boolean, RolePushException> rolePushResultChecked =
                RoleUtil.makeCheckedRuleRequestFxResult(rolePushResult);
            Futures.addCallback(rolePushResult, new FutureCallback<Boolean>(){
                @Override
                public void onSuccess(Boolean result){
                    LOG.debug("setSlaveRole: Controller is set as a SLAVE for {}", targetSwitchDPId.toString());
                }
                @Override
                public void onFailure(Throwable e){
                    LOG.error("setSlaveRole: Role request to set controller as a SLAVE failed for {}",
                            targetSwitchDPId.toString(), e);
                }
            });
        } else {
            LOG.warn("setSlaveRole: sessionContext is not set. Device is not connected anymore");
        }
    }



    private void onDeviceOwnershipChangedToBecomeEqual(final EntityOwnershipChange ownershipChange) {
        OfpRole newRole =  OfpRole.BECOMEEQUAL;
        final Entity entity = ownershipChange.getEntity();
        SessionContext sessionContext = entsession.get(entity)!=null?entsession.get(entity).getContext():null;
        if (ownershipChange.isOwner()) {
            LOG.info("onDeviceOwnershipChanged: Set controller as a EQUAL controller because " +
                    "it's the OWNER of the {}", entity);
            newRole =  OfpRole.BECOMEEQUAL;
        }
        if (sessionContext != null) {
            //Register the RPC, given *this* controller instance is going to be master owner.
            //If role registration fails for this node, it will deregister as a candidate for
            //ownership and that will make this controller non-owner and it will deregister the
            // router rpc.
            setDeviceOwnershipState(entity,newRole==OfpRole.BECOMEEQUAL);
            registerRoutedRPCForSwitch(entsession.get(entity));

            final String targetSwitchDPId = sessionContext.getFeatures().getDatapathId().toString();
            RolePushTask task = new RolePushTask(newRole, sessionContext);
            ListenableFuture<Boolean> rolePushResult = pool.submit(task);
            final CheckedFuture<Boolean, RolePushException> rolePushResultChecked =
                RoleUtil.makeCheckedRuleRequestFxResult(rolePushResult);
            Futures.addCallback(rolePushResult, new FutureCallback<Boolean>(){
                @Override
                public void onSuccess(Boolean result){
                    LOG.info("onDeviceOwnershipChanged: Controller is successfully set as a " +
                            "EQUAL controller for {}", targetSwitchDPId);
                    entsession.get(entity).getOfSwitch().sendEmptyTableFeatureRequest();
                    sendNodeAddedNotification(entsession.get(entity));
                }
                @Override
                public void onFailure(Throwable e) {
                    LOG.warn("onDeviceOwnershipChanged: Controller is not able to set the "+ "EQUAL role for {}.",
                            targetSwitchDPId, e);
                    LOG.info("onDeviceOwnershipChanged: ..and this *instance* is owner of the device {}. "
                                    + "Closing the registration, so other entity can become owner "
                                    + "and attempt to be master controller.",  targetSwitchDPId);
                    EntityOwnershipCandidateRegistration ownershipRegistrent = entRegistrationMap.get(entity);
                    if (ownershipRegistrent != null) {
                        setDeviceOwnershipState(entity, false);
                        ownershipRegistrent.close();
                        MDSwitchMetaData switchMetadata = entsession.get(entity);
                        if (switchMetadata != null) {
                            switchMetadata.setIsOwnershipInitialized(false);
                            // We can probably leave deregistration till the
                            // node ownerhsip change.
                            // But that can probably cause some race condition.
                            deregisterRoutedRPCForSwitch(switchMetadata);
                        }
                    }
                    LOG.info("onDeviceOwnershipChanged: ..and registering it back to participate in "
                                    + "ownership and re-try");
                    EntityOwnershipCandidateRegistration entityRegistration;
                    try {
                        entityRegistration = entityOwnershipService.registerCandidate(entity);
                        entRegistrationMap.put(entity, entityRegistration);
                        LOG.info("onDeviceOwnershipChanged: re-registered candidate for "
                                        + "ownership of the {}", targetSwitchDPId);
                    } catch (CandidateAlreadyRegisteredException ex) {
                        // we can log and move for this error, as listener is
                        // present and role changes will be served.
                        LOG.error("onDeviceOwnershipChanged: *Surprisingly* Entity is already "
                                        + "registered with EntityOwnershipService : {}", targetSwitchDPId, ex);
                    }
                }
            });
        } else {
            LOG.warn("onDeviceOwnershipChanged: sessionContext is not available. Releasing ownership of the device");
            EntityOwnershipCandidateRegistration ownershipRegistrant = entRegistrationMap.get(entity);
            if (ownershipRegistrant != null) {
                ownershipRegistrant.close();
            }
        }
    }


    public void onDeviceOwnershipChanged(final EntityOwnershipChange ownershipChange) {
        final OfpRole newRole;
        final Entity entity = ownershipChange.getEntity();
        SessionContext sessionContext = entsession.get(entity)!=null?entsession.get(entity).getContext():null;
        if (ownershipChange.isOwner()) {
            LOG.info("onDeviceOwnershipChanged: Set controller as a MASTER controller because " +
                    "it's the OWNER of the {}", entity);
            newRole =  OfpRole.BECOMEMASTER;
        }
        else {

            newRole =  OfpRole.BECOMESLAVE;
            if(sessionContext != null && ownershipChange.hasOwner()) {
                LOG.info("onDeviceOwnershipChanged: Set controller as a SLAVE controller because " +
                        "it's not the OWNER of the {}", entity);

                if(ownershipChange.wasOwner()) {
                    setDeviceOwnershipState(entity,false);
                    deregisterRoutedRPCForSwitch(entsession.get(entity));
                    // You don't have to explicitly set role to Slave in this case,
                    // because other controller will be taking over the master role
                    // and that will force other controller to become slave.

                    //Added in order to properly handle set role to SLAVE when called from role manager
                    setSlaveRole(sessionContext);
                    sendNodeAddedNotification(entsession.get(entity));
                } else {
                    boolean isOwnershipInitialized = entsession.get(entity).getIsOwnershipInitialized();
                    setDeviceOwnershipState(entity,false);
                    if (!isOwnershipInitialized) {
                        setSlaveRole(sessionContext);
                        sendNodeAddedNotification(entsession.get(entity));
                    }
                }
            }
            return;
        }
        if (sessionContext != null) {
            //Register the RPC, given *this* controller instance is going to be master owner.
            //If role registration fails for this node, it will deregister as a candidate for
            //ownership and that will make this controller non-owner and it will deregister the
            // router rpc.
            setDeviceOwnershipState(entity,newRole==OfpRole.BECOMEMASTER);
            registerRoutedRPCForSwitch(entsession.get(entity));

            final String targetSwitchDPId = sessionContext.getFeatures().getDatapathId().toString();
            RolePushTask task = new RolePushTask(newRole, sessionContext);
            ListenableFuture<Boolean> rolePushResult = pool.submit(task);
            final CheckedFuture<Boolean, RolePushException> rolePushResultChecked =
                RoleUtil.makeCheckedRuleRequestFxResult(rolePushResult);
            Futures.addCallback(rolePushResult, new FutureCallback<Boolean>(){
                @Override
                public void onSuccess(Boolean result){
                    LOG.info("onDeviceOwnershipChanged: Controller is successfully set as a " +
                            "MASTER controller for {}", targetSwitchDPId);
                    entsession.get(entity).getOfSwitch().sendEmptyTableFeatureRequest();
                    sendNodeAddedNotification(entsession.get(entity));

                }
                @Override
                public void onFailure(Throwable e){

                    LOG.warn("onDeviceOwnershipChanged: Controller is not able to set the " +
                            "MASTER role for {}.", targetSwitchDPId,e);
                    if(newRole == OfpRole.BECOMEMASTER) {
                        LOG.info("onDeviceOwnershipChanged: ..and this *instance* is owner of the device {}. " +
                                "Closing the registration, so other entity can become owner " +
                                "and attempt to be master controller.",targetSwitchDPId);

                        EntityOwnershipCandidateRegistration ownershipRegistrent = entRegistrationMap.get(entity);
                        if (ownershipRegistrent != null) {
                            setDeviceOwnershipState(entity,false);
                            ownershipRegistrent.close();
                            MDSwitchMetaData switchMetadata = entsession.get(entity);
                            if(switchMetadata != null){
                                switchMetadata.setIsOwnershipInitialized(false);
                                //We can probably leave deregistration till the node ownerhsip change.
                                //But that can probably cause some race condition.
                                deregisterRoutedRPCForSwitch(switchMetadata);
                            }
                        }

                        LOG.info("onDeviceOwnershipChanged: ..and registering it back to participate in " +
                                "ownership and re-try");

                        EntityOwnershipCandidateRegistration entityRegistration;
                        try {
                            entityRegistration = entityOwnershipService.registerCandidate(entity);
                            entRegistrationMap.put(entity, entityRegistration);
                            LOG.info("onDeviceOwnershipChanged: re-registered candidate for " +
                                    "ownership of the {}", targetSwitchDPId );
                        } catch (CandidateAlreadyRegisteredException ex) {
                            // we can log and move for this error, as listener is present and role changes will be served.
                            LOG.error("onDeviceOwnershipChanged: *Surprisingly* Entity is already " +
                                    "registered with EntityOwnershipService : {}", targetSwitchDPId, ex );
                        }

                    } else {
                        LOG.error("onDeviceOwnershipChanged : Not able to set role {} for " +
                                " {}", newRole == OfpRole.BECOMEMASTER?"MASTER":"SLAVE", targetSwitchDPId);
                    }
                }
            });
        } else {
            LOG.warn("onDeviceOwnershipChanged: sessionContext is not available. Releasing ownership of the device");
            EntityOwnershipCandidateRegistration ownershipRegistrant = entRegistrationMap.get(entity);
            if (ownershipRegistrant != null) {
                ownershipRegistrant.close();
            }
        }
    }

    public void unregisterEntityOwnershipRequest(NodeId nodeId) {
        Entity entity = new Entity(DEVICE_TYPE, nodeId.getValue());
        entsession.remove(entity);
        EntityOwnershipCandidateRegistration entRegCandidate = entRegistrationMap.get(entity);
        if(entRegCandidate != null){
            LOG.info("unregisterEntityOwnershipRequest: Unregister controller entity ownership " +
                    "request for {}", nodeId);
            entRegCandidate.close();
            entRegistrationMap.remove(entity);
        }
    }

    @Override
    public void onTransactionChainFailed(final TransactionChain<?, ?> chain, final AsyncTransaction<?, ?> transaction,
           final Throwable cause) {
    }

    @Override
    public void onTransactionChainSuccessful(final TransactionChain<?, ?> chain) {
       // NOOP
    }

    private void registerRoutedRPCForSwitch(MDSwitchMetaData entityMetadata) {
        // Routed RPC registration is only done when *this* instance is owner of
        // the entity.
        if(entityMetadata.getOfSwitch().isEntityOwner()) {
            if (!entityMetadata.isRPCRegistrationDone.get()) {
                entityMetadata.setIsRPCRegistrationDone(true);
                CompositeObjectRegistration<ModelDrivenSwitch> registration =
                        entityMetadata.getOfSwitch().register(entityMetadata.getRpcProviderRegistry());

                entityMetadata.getContext().setProviderRegistration(registration);

                LOG.info("registerRoutedRPCForSwitch: Registered routed rpc for ModelDrivenSwitch {}",
                        entityMetadata.getOfSwitch().getNodeId().getValue());
            }
        } else {
            LOG.info("registerRoutedRPCForSwitch: Skipping routed rpc registration for ModelDrivenSwitch {}",
                    entityMetadata.getOfSwitch().getNodeId().getValue());
        }
    }

    private void deregisterRoutedRPCForSwitch(MDSwitchMetaData entityMetadata) {

        CompositeObjectRegistration<ModelDrivenSwitch> registration = entityMetadata.getContext().getProviderRegistration();
        if (null != registration) {
            registration.close();
            entityMetadata.getContext().setProviderRegistration(null);
            entityMetadata.setIsRPCRegistrationDone(false);
        }
        LOG.info("deregisterRoutedRPCForSwitch: De-registered routed rpc for ModelDrivenSwitch {}",
                entityMetadata.getOfSwitch().getNodeId().getValue());
    }

    private void sendNodeAddedNotification(MDSwitchMetaData entityMetadata) {
        //Node added notification need to be sent irrespective of whether
        // *this* instance is owner of the entity or not. Because yang notifications
        // are local, and we should maintain the behavior across the application.
        LOG.info("sendNodeAddedNotification: Node Added notification is sent for ModelDrivenSwitch {}",
                entityMetadata.getOfSwitch().getNodeId().getValue());

        entityMetadata.getContext().getNotificationEnqueuer().enqueueNotification(
                entityMetadata.getWrappedNotification());

        //Send multipart request to get other details of the switch.
        entityMetadata.getOfSwitch().requestSwitchDetails();
    }

    private void setDeviceOwnershipState(Entity entity, boolean isMaster) {
        MDSwitchMetaData entityMetadata = entsession.get(entity);
        entityMetadata.setIsOwnershipInitialized(true);
        entityMetadata.getOfSwitch().setEntityOwnership(isMaster);
    }


    public void callOnDeviceOwnershipChanged(SessionContext context, ControllerRole controllerRole){
        LOG.info("callOnDeviceOwnershipChanged(SessionContext context, ControllerRole controllerRole)");
        
        //Prevent cross-compile exception when compiled on higher java version
        //used only for the .getKeySet()
        Map<Entity, MDSwitchMetaData> es = new ConcurrentHashMap<>(entsession);
        
        for(Entity entity : es.keySet()){
            if(entsession.get(entity).getContext().getFeatures().getDatapathId().toString()
                    .equals(context.getFeatures().getDatapathId().toString())){
                //new EntityOwnershipChange(entity, wasOwner, isOwner, hasOwner)
                if(controllerRole.equals(ControllerRole.OFPCRROLEMASTER)){
                    EntityOwnershipChange entityOwnershipChange = new EntityOwnershipChange(entity, false, true, true);
                    LOG.info("onDeviceOwnershipChanged(entityOwnershipChange): "+entityOwnershipChange.toString());
                    onDeviceOwnershipChanged(entityOwnershipChange);
                }
                if(controllerRole.equals(ControllerRole.OFPCRROLEEQUAL)){
                    EntityOwnershipChange entityOwnershipChange = new EntityOwnershipChange(entity, false, true, true);
                    LOG.info("onDeviceOwnershipChanged(entityOwnershipChange): "+entityOwnershipChange.toString());
                    onDeviceOwnershipChangedToBecomeEqual(entityOwnershipChange);
                }
                if(controllerRole.equals(ControllerRole.OFPCRROLESLAVE)){
                    EntityOwnershipChange entityOwnershipChange = new EntityOwnershipChange(entity, true, false, true);
                    LOG.info("onDeviceOwnershipChanged(entityOwnershipChange): "+entityOwnershipChange.toString());
                    onDeviceOwnershipChanged(entityOwnershipChange);
                }
            }
        }
    }


    public static NodeId nodeIdFromDatapathId(final BigInteger datapathId) {
        // FIXME: Convert to textual representation of datapathID
        String current = String.valueOf(datapathId);
        return new NodeId("openflow:" + current);
        }


    private class MDSwitchMetaData {

        final private ModelDrivenSwitch ofSwitch;
        final private SessionContext context;
        final private NotificationQueueWrapper wrappedNotification;
        final private RpcProviderRegistry rpcProviderRegistry;
        final private AtomicBoolean isRPCRegistrationDone = new AtomicBoolean(false);
        final private AtomicBoolean isOwnershipInitialized = new AtomicBoolean(false);

        MDSwitchMetaData(ModelDrivenSwitch ofSwitch,
                         SessionContext context,
                         NotificationQueueWrapper wrappedNotification,
                         RpcProviderRegistry rpcProviderRegistry) {
            this.ofSwitch = ofSwitch;
            this.context = context;
            this.wrappedNotification = wrappedNotification;
            this.rpcProviderRegistry = rpcProviderRegistry;
        }

        public ModelDrivenSwitch getOfSwitch() {
            return ofSwitch;
        }

        public SessionContext getContext() {
            return context;
        }

        public NotificationQueueWrapper getWrappedNotification() {
            return wrappedNotification;
        }

        public RpcProviderRegistry getRpcProviderRegistry() {
            return rpcProviderRegistry;
        }

        public AtomicBoolean getIsRPCRegistrationDone() {
            return isRPCRegistrationDone;
        }

        public void setIsRPCRegistrationDone(boolean isRPCRegistrationDone) {
            this.isRPCRegistrationDone.set(isRPCRegistrationDone);
        }

        public boolean getIsOwnershipInitialized() {
            return isOwnershipInitialized.get();
        }

        public void setIsOwnershipInitialized( boolean ownershipState) {
            this.isOwnershipInitialized.set(ownershipState);
        }
    }
}
