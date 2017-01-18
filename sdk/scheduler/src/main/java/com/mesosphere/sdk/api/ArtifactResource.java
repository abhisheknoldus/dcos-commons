package com.mesosphere.sdk.api;

import com.mesosphere.sdk.config.ConfigStore;
import com.mesosphere.sdk.config.ConfigStoreException;
import com.mesosphere.sdk.config.ConfigStoreException.Reason;
import com.mesosphere.sdk.specification.ConfigFileSpec;
import com.mesosphere.sdk.specification.PodSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A read-only API for accessing file artifacts (e.g. config templates) for retrieval by executors.
 */
@Path("/v1/artifacts")
public class ArtifactResource {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ConfigStore<ServiceSpec> configStore;

    public ArtifactResource(ConfigStore<ServiceSpec> configStore) {
        this.configStore = configStore;
    }

    /**
     * Produces the content of the requested configuration template, or returns an error if that template doesn't exist
     * or the data couldn't be read.
     */
    @Path("/template/{configurationId}/{podType}/{taskName}/{configName}")
    @GET
    public Response getTemplate(
            @PathParam("configurationId") String configurationId,
            @PathParam("podType") String podType,
            @PathParam("taskName") String taskName,
            @PathParam("configName") String configName) {
        logger.info("Attempting to fetch template from config '{}' with pod '{}', task '{}', and template '{}'",
                configurationId, podType, taskName, configName);
        UUID uuid;
        try {
            uuid = UUID.fromString(configurationId);
        } catch (IllegalArgumentException ex) {
            logger.warn(String.format(
                    "Failed to parse requested configuration id as a UUID: '%s'", configurationId), ex);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        ServiceSpec serviceSpec;
        try {
            serviceSpec = configStore.fetch(uuid);
        } catch (ConfigStoreException ex) {
            if (ex.getReason() == Reason.NOT_FOUND) {
                logger.warn(String.format("Requested configuration '%s' doesn't exist", configurationId), ex);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            logger.error(String.format(
                    "Failed to fetch requested configuration with id '%s'", configurationId), ex);
            return Response.serverError().build();
        }
        try {
            ConfigFileSpec config = getConfigFile(getTask(getPod(serviceSpec, podType), taskName), configName);
            return Response.ok(config.getTemplateContent(), MediaType.TEXT_PLAIN_TYPE).build();
        } catch (Exception ex) {
            logger.warn(String.format(
                    "Couldn't find requested template in config '%s'", configurationId), ex);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    private static PodSpec getPod(ServiceSpec serviceSpec, String podType) throws Exception {
        Optional<PodSpec> podOptional =
                serviceSpec.getPods().stream().filter(pod -> podType.equals(pod.getType())).findFirst();
        if (!podOptional.isPresent()) {
            List<String> availablePodTypes =
                    serviceSpec.getPods().stream().map(podSpec -> podSpec.getType()).collect(Collectors.toList());
            throw new Exception(String.format(
                    "Couldn't find pod of type '%s'. Known pod types are: %s", podType, availablePodTypes));
        }
        return podOptional.get();
    }

    private static TaskSpec getTask(PodSpec podSpec, String taskName) throws Exception {
        Optional<TaskSpec> taskOptional =
                podSpec.getTasks().stream().filter(task -> taskName.equals(task.getName())).findFirst();
        if (!taskOptional.isPresent()) {
            List<String> availableTaskNames =
                    podSpec.getTasks().stream().map(taskSpec -> taskSpec.getName()).collect(Collectors.toList());
            throw new Exception(String.format(
                    "Couldn't find task named '%s' within pod '%s'. Known task names are: %s",
                    taskName, podSpec.getType(), availableTaskNames));
        }
        return taskOptional.get();
    }

    private static ConfigFileSpec getConfigFile(TaskSpec taskSpec, String configName) throws Exception {
        Optional<ConfigFileSpec> configOptional =
                taskSpec.getConfigFiles().stream().filter(config -> configName.equals(config.getName())).findFirst();
        if (!configOptional.isPresent()) {
            List<String> availableConfigNames =
                    taskSpec.getConfigFiles().stream().map(config -> config.getName()).collect(Collectors.toList());
            throw new Exception(String.format(
                    "Couldn't find config named '%s' within task '%s'. Known config names are: %s",
                    configName, taskSpec.getName(), availableConfigNames));
        }
        return configOptional.get();
    }
}
