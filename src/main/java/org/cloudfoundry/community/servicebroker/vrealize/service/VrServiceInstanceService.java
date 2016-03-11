package org.cloudfoundry.community.servicebroker.vrealize.service;

import org.apache.log4j.Logger;
import org.cloudfoundry.community.servicebroker.vrealize.VraClient;
import org.cloudfoundry.community.servicebroker.vrealize.persistance.VrServiceInstance;
import org.cloudfoundry.community.servicebroker.vrealize.persistance.VrServiceInstanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.servicebroker.exception.ServiceBrokerException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceExistsException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceUpdateNotSupportedException;
import org.springframework.cloud.servicebroker.model.*;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.stereotype.Service;

@Service
public class VrServiceInstanceService implements ServiceInstanceService {

    private static final Logger LOG = Logger
            .getLogger(VrServiceInstanceService.class);

    @Autowired
    VraClient vraClient;

    @Autowired
    CatalogService catalogService;

    @Autowired
    VrServiceInstanceRepository repository;

    private GetLastServiceOperationResponse lastOperation;

    public GetLastServiceOperationResponse getServiceInstanceLastOperation() {
        return lastOperation;
    }

    public VrServiceInstanceService withLastOperation(GetLastServiceOperationResponse lastOperation) {
        this.lastOperation = lastOperation;
        return this;
    }

    public VrServiceInstance getServiceInstance(String id) {

        if (id == null || getInstance(id) == null) {
            LOG.warn("service instance with id: " + id + " not found!");
            return null;
        }

        VrServiceInstance instance = getInstance(id);

        // check the last operation
        GetLastServiceOperationResponse silo = instance
                .getServiceInstanceLastOperation();
        if (silo == null || silo.getState() == null) {
            LOG.error("ServiceInstance: " + id + " has no last operation.");
            deleteInstance(instance);
            return null;
        }

        // if the instance is not in progress just return it.
        if (!instance.isInProgress()) {
            return instance;
        }

        // if still in progress, let's check up on things...
        String currentRequestId = silo.getDescription();
        if (currentRequestId == null) {
            LOG.error("ServiceInstance: " + id + " last operation has no id.");
            deleteInstance(instance);
            return null;
        }

        OperationState state = instance.getServiceInstanceLastOperation().getState();
        LOG.info("service instance id: " + id + " request id: "
                + currentRequestId + " is in state: " + state);

        LOG.info("checking on status of request id: " + currentRequestId);
        GetLastServiceOperationResponse status;
        try {
            status = vraClient.getRequestStatus(instance);
            LOG.info("request: " + id + " status is: " + status.getState());
        } catch (ServiceBrokerException e) {
            LOG.error("unable to get status of request: " + id, e);
            return null;
        }

        instance.withLastOperation(status);

        // if this is a delete request and was successful, remove the instance
        if (instance.isCurrentOperationSuccessful()
                && instance.isCurrentOperationDelete()) {
            deleteInstance(instance);
        }

        // otherwise save the instance with the new last operation
        return saveInstance(instance);
    }

    @Override
    public CreateServiceInstanceResponse createServiceInstance(CreateServiceInstanceRequest request)
            throws ServiceInstanceExistsException, ServiceBrokerException {

        if (getInstance(request.getServiceInstanceId()) != null) {
            throw new ServiceInstanceExistsException(request.getServiceInstanceId(), request.getServiceDefinitionId());
        }

        ServiceDefinition sd = catalogService.getServiceDefinition(request
                .getServiceDefinitionId());

        if (sd == null) {
            throw new ServiceBrokerException(
                    "Unable to find service definition with id: "
                            + request.getServiceDefinitionId());
        }

        LOG.info("creating service instance: " + request.getServiceInstanceId()
                + " service definition: " + request.getServiceDefinitionId());

        VrServiceInstance instance = vraClient.createInstance(request, sd);

        instance = saveInstance(instance);

        LOG.info("registered service instance: "
                + instance.getId()
                + " requestId: "
                + instance.getMetadata().get(
                VrServiceInstance.CREATE_REQUEST_ID));

        return new CreateServiceInstanceResponse().withAsync(true);
    }

    @Override
    public GetLastServiceOperationResponse getLastOperation(GetLastServiceOperationRequest request) {
        VrServiceInstance si = getInstance(request.getServiceInstanceId());
        if (si == null) {
            throw new ServiceInstanceDoesNotExistException(request.getServiceInstanceId());
        }

        return si.getServiceInstanceLastOperation();
    }

    @Override
    public DeleteServiceInstanceResponse deleteServiceInstance(DeleteServiceInstanceRequest request) throws ServiceBrokerException {

        VrServiceInstance instance = getInstance(request.getServiceInstanceId());
        if (instance == null) {
            throw new ServiceBrokerException("Service instance: "
                    + request.getServiceInstanceId() + " not found.");
        }

        LOG.info("deleting service instance: " + request.getServiceInstanceId());

        instance = deleteInstance(instance);

        LOG.info("unregistering service instance: "
                + instance.getId()
                + " requestId: "
                + instance.getMetadata().get(
                VrServiceInstance.DELETE_REQUEST_ID));

        return new DeleteServiceInstanceResponse().withAsync(true);
    }

    @Override
    public UpdateServiceInstanceResponse updateServiceInstance(UpdateServiceInstanceRequest request) throws ServiceInstanceUpdateNotSupportedException,
            ServiceBrokerException, ServiceInstanceDoesNotExistException {

        throw new ServiceInstanceUpdateNotSupportedException(
                "vRealize services are not updatable.");
    }

    private VrServiceInstance getInstance(String id) {
        if (id == null) {
            return null;
        }
        return repository.findOne(id);
    }

    VrServiceInstance deleteInstance(VrServiceInstance instance) {
        repository.delete(instance.getId());
        return instance;
    }

    VrServiceInstance saveInstance(VrServiceInstance instance) {
        return repository.save(instance);
    }
}