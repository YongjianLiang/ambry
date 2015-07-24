package com.github.ambry.server;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.DataNodeId;
import com.github.ambry.clustermap.HardwareState;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.clustermap.PartitionState;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.messageformat.DeleteMessageFormatInputStream;
import com.github.ambry.messageformat.MessageFormatErrorCodes;
import com.github.ambry.messageformat.MessageFormatException;
import com.github.ambry.messageformat.MessageFormatFlags;
import com.github.ambry.messageformat.MessageFormatInputStream;
import com.github.ambry.messageformat.MessageFormatMetrics;
import com.github.ambry.messageformat.MessageFormatSend;
import com.github.ambry.messageformat.MessageFormatWriteSet;
import com.github.ambry.messageformat.PutMessageFormatInputStream;
import com.github.ambry.metrics.MetricsHistogram;
import com.github.ambry.network.CompositeSend;
import com.github.ambry.network.NetworkRequestMetrics;
import com.github.ambry.network.Request;
import com.github.ambry.network.RequestResponseChannel;
import com.github.ambry.network.Send;
import com.github.ambry.notification.BlobReplicaSourceType;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.protocol.DeleteRequest;
import com.github.ambry.protocol.DeleteResponse;
import com.github.ambry.protocol.GetOptions;
import com.github.ambry.protocol.GetRequest;
import com.github.ambry.protocol.GetResponse;
import com.github.ambry.protocol.PartitionRequestInfo;
import com.github.ambry.protocol.PartitionResponseInfo;
import com.github.ambry.protocol.ReplicaMetadataRequest;
import com.github.ambry.protocol.ReplicaMetadataRequestInfo;
import com.github.ambry.protocol.ReplicaMetadataResponse;
import com.github.ambry.protocol.ReplicaMetadataResponseInfo;
import com.github.ambry.protocol.RequestOrResponseType;
import com.github.ambry.protocol.PutRequest;
import com.github.ambry.protocol.PutResponse;
import com.github.ambry.replication.ReplicationManager;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.store.FindInfo;
import com.github.ambry.store.FindToken;
import com.github.ambry.store.FindTokenFactory;
import com.github.ambry.store.MessageInfo;
import com.github.ambry.store.Store;
import com.github.ambry.store.StoreErrorCodes;
import com.github.ambry.store.StoreException;
import com.github.ambry.store.StoreGetOptions;
import com.github.ambry.store.StoreInfo;
import com.github.ambry.store.StoreKeyFactory;
import com.github.ambry.store.StoreManager;
import com.github.ambry.utils.SystemTime;
import com.github.ambry.utils.Utils;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The main request implementation class. All requests to the server are
 * handled by this class
 */

public class AmbryRequests implements RequestAPI {

  private StoreManager storeManager;
  private final RequestResponseChannel requestResponseChannel;
  private Logger logger = LoggerFactory.getLogger(getClass());
  private Logger publicAccessLogger = LoggerFactory.getLogger("PublicAccessLogger");
  private final ClusterMap clusterMap;
  private final DataNodeId currentNode;
  private final ServerMetrics metrics;
  private final MessageFormatMetrics messageFormatMetrics;
  private final FindTokenFactory findTokenFactory;
  private final NotificationSystem notification;
  private final ReplicationManager replicationManager;
  private final StoreKeyFactory storeKeyFactory;

  public AmbryRequests(StoreManager storeManager, RequestResponseChannel requestResponseChannel, ClusterMap clusterMap,
      DataNodeId nodeId, MetricRegistry registry, FindTokenFactory findTokenFactory,
      NotificationSystem operationNotification, ReplicationManager replicationManager,
      StoreKeyFactory storeKeyFactory) {
    this.storeManager = storeManager;
    this.requestResponseChannel = requestResponseChannel;
    this.clusterMap = clusterMap;
    this.currentNode = nodeId;
    this.metrics = new ServerMetrics(registry);
    this.messageFormatMetrics = new MessageFormatMetrics(registry);
    this.findTokenFactory = findTokenFactory;
    this.notification = operationNotification;
    this.replicationManager = replicationManager;
    this.storeKeyFactory = storeKeyFactory;
  }

  public void handleRequests(Request request)
      throws InterruptedException {
    try {
      DataInputStream stream = new DataInputStream(request.getInputStream());
      RequestOrResponseType type = RequestOrResponseType.values()[stream.readShort()];
      switch (type) {
        case PutRequest:
          handlePutRequest(request);
          break;
        case GetRequest:
          handleGetRequest(request);
          break;
        case DeleteRequest:
          handleDeleteRequest(request);
          break;
        case ReplicaMetadataRequest:
          handleReplicaMetadataRequest(request);
          break;
        default:
          throw new UnsupportedOperationException("Request type not supported");
      }
    } catch (Exception e) {
      logger.error("Error while handling request " + request + " closing connection", e);
      requestResponseChannel.closeConnection(request);
    }
  }

  public void handlePutRequest(Request request)
      throws IOException, InterruptedException {
    PutRequest putRequest = PutRequest.readFrom(new DataInputStream(request.getInputStream()), clusterMap);
    long requestQueueTime = SystemTime.getInstance().milliseconds() - request.getStartTimeInMs();
    long totalTimeSpent = requestQueueTime;
    metrics.putBlobRequestQueueTimeInMs.update(requestQueueTime);
    metrics.putBlobRequestRate.mark();
    long startTime = SystemTime.getInstance().milliseconds();
    PutResponse response = null;
    try {
      ServerErrorCode error = validateRequest(putRequest.getBlobId().getPartition(), true);
      if (error != ServerErrorCode.No_Error) {
        logger.error("Validating put request failed with error {} for request {}", error, putRequest);
        response = new PutResponse(putRequest.getCorrelationId(), putRequest.getClientId(), error);
      } else {
        MessageFormatInputStream stream =
            new PutMessageFormatInputStream(putRequest.getBlobId(), putRequest.getBlobProperties(),
                putRequest.getUsermetadata(), putRequest.getData(), putRequest.getBlobProperties().getBlobSize());
        MessageInfo info = new MessageInfo(putRequest.getBlobId(), stream.getSize(), Utils
            .addSecondsToEpochTime(putRequest.getBlobProperties().getCreationTimeInMs(),
                putRequest.getBlobProperties().getTimeToLiveInSeconds()));
        ArrayList<MessageInfo> infoList = new ArrayList<MessageInfo>();
        infoList.add(info);
        MessageFormatWriteSet writeset = new MessageFormatWriteSet(stream, infoList, false);
        Store storeToPut = storeManager.getStore(putRequest.getBlobId().getPartition());
        storeToPut.put(writeset);
        response = new PutResponse(putRequest.getCorrelationId(), putRequest.getClientId(), ServerErrorCode.No_Error);
        metrics.blobSizeInBytes.update(putRequest.getBlobProperties().getBlobSize());
        metrics.blobUserMetadataSizeInBytes.update(putRequest.getUsermetadata().limit());
        if (notification != null) {
          notification
              .onBlobReplicaCreated(currentNode.getHostname(), currentNode.getPort(), putRequest.getBlobId().getID(),
                  BlobReplicaSourceType.PRIMARY);
        }
      }
    } catch (StoreException e) {
      logger.error("Store exception on a put with error code " + e.getErrorCode() + " for request " + putRequest, e);
      if (e.getErrorCode() == StoreErrorCodes.Already_Exist) {
        metrics.idAlreadyExistError.inc();
      } else if (e.getErrorCode() == StoreErrorCodes.IOError) {
        metrics.storeIOError.inc();
      } else {
        metrics.unExpectedStorePutError.inc();
      }
      response = new PutResponse(putRequest.getCorrelationId(), putRequest.getClientId(),
          ErrorMapping.getStoreErrorMapping(e.getErrorCode()));
    } catch (Exception e) {
      logger.error("Unknown exception on a put for request " + putRequest, e);
      response =
          new PutResponse(putRequest.getCorrelationId(), putRequest.getClientId(), ServerErrorCode.Unknown_Error);
    } finally {
      long processingTime = SystemTime.getInstance().milliseconds() - startTime;
      totalTimeSpent += processingTime;
      publicAccessLogger.info("{} {} processingTime {}", putRequest, response, processingTime);
      metrics.putBlobProcessingTimeInMs.update(processingTime);
    }
    sendPutResponse(requestResponseChannel, response, request,
        new HistogramMeasurement(metrics.putBlobResponseQueueTimeInMs),
        new HistogramMeasurement(metrics.putBlobSendTimeInMs), new HistogramMeasurement(metrics.putBlobTotalTimeInMs),
        totalTimeSpent, putRequest.getBlobProperties().getBlobSize(), metrics);
  }

  public void handleGetRequest(Request request)
      throws IOException, InterruptedException {
    GetRequest getRequest = GetRequest.readFrom(new DataInputStream(request.getInputStream()), clusterMap);
    HistogramMeasurement responseQueueTimeMeasurement = null;
    HistogramMeasurement responseSendTimeMeasurement = null;
    HistogramMeasurement responseTotalTimeMeasurement = null;
    long requestQueueTime = SystemTime.getInstance().milliseconds() - request.getStartTimeInMs();
    long totalTimeSpent = requestQueueTime;
    if (getRequest.getMessageFormatFlag() == MessageFormatFlags.Blob) {
      metrics.getBlobRequestQueueTimeInMs.update(requestQueueTime);
      metrics.getBlobRequestRate.mark();
      responseQueueTimeMeasurement = new HistogramMeasurement(metrics.getBlobResponseQueueTimeInMs);
      responseSendTimeMeasurement = new HistogramMeasurement(metrics.getBlobSendTimeInMs);
      responseTotalTimeMeasurement = new HistogramMeasurement(metrics.getBlobTotalTimeInMs);
    } else if (getRequest.getMessageFormatFlag() == MessageFormatFlags.BlobProperties) {
      metrics.getBlobPropertiesRequestQueueTimeInMs.update(requestQueueTime);
      metrics.getBlobPropertiesRequestRate.mark();
      responseQueueTimeMeasurement = new HistogramMeasurement(metrics.getBlobPropertiesResponseQueueTimeInMs);
      responseSendTimeMeasurement = new HistogramMeasurement(metrics.getBlobPropertiesSendTimeInMs);
      responseTotalTimeMeasurement = new HistogramMeasurement(metrics.getBlobPropertiesTotalTimeInMs);
    } else if (getRequest.getMessageFormatFlag() == MessageFormatFlags.BlobUserMetadata) {
      metrics.getBlobUserMetadataRequestQueueTimeInMs.update(requestQueueTime);
      metrics.getBlobUserMetadataRequestRate.mark();
      responseQueueTimeMeasurement = new HistogramMeasurement(metrics.getBlobUserMetadataResponseQueueTimeInMs);
      responseSendTimeMeasurement = new HistogramMeasurement(metrics.getBlobUserMetadataSendTimeInMs);
      responseTotalTimeMeasurement = new HistogramMeasurement(metrics.getBlobUserMetadataTotalTimeInMs);
    } else if (getRequest.getMessageFormatFlag() == MessageFormatFlags.All) {
      metrics.getBlobAllRequestQueueTimeInMs.update(requestQueueTime);
      metrics.getBlobAllRequestRate.mark();
      responseQueueTimeMeasurement = new HistogramMeasurement(metrics.getBlobAllResponseQueueTimeInMs);
      responseSendTimeMeasurement = new HistogramMeasurement(metrics.getBlobAllSendTimeInMs);
      responseTotalTimeMeasurement = new HistogramMeasurement(metrics.getBlobAllTotalTimeInMs);
    }
    long startTime = SystemTime.getInstance().milliseconds();
    GetResponse response = null;
    try {
      List<Send> messagesToSendList = new ArrayList<Send>(getRequest.getPartitionInfoList().size());
      List<PartitionResponseInfo> partitionResponseInfoList =
          new ArrayList<PartitionResponseInfo>(getRequest.getPartitionInfoList().size());
      for (PartitionRequestInfo partitionRequestInfo : getRequest.getPartitionInfoList()) {
        ServerErrorCode error = validateRequest(partitionRequestInfo.getPartition(), false);
        if (error != ServerErrorCode.No_Error) {
          logger.error("Validating get request failed for partition {} with error {}",
              partitionRequestInfo.getPartition(), error);
          PartitionResponseInfo partitionResponseInfo =
              new PartitionResponseInfo(partitionRequestInfo.getPartition(), error);
          partitionResponseInfoList.add(partitionResponseInfo);
        } else {
          try {
            Store storeToGet = storeManager.getStore(partitionRequestInfo.getPartition());
            EnumSet<StoreGetOptions> storeGetOptions = EnumSet.noneOf(StoreGetOptions.class);
            // Currently only one option is supported.
            if (getRequest.getGetOptions() == GetOptions.Include_Expired_Blobs) {
              storeGetOptions = EnumSet.of(StoreGetOptions.Store_Include_Expired);
            }
            if (getRequest.getGetOptions() == GetOptions.Include_Deleted_Blobs) {
              storeGetOptions = EnumSet.of(StoreGetOptions.Store_Include_Deleted);
            }
            if (getRequest.getGetOptions() == GetOptions.Include_All) {
              storeGetOptions =
                  EnumSet.of(StoreGetOptions.Store_Include_Deleted, StoreGetOptions.Store_Include_Expired);
            }
            StoreInfo info = storeToGet.get(partitionRequestInfo.getBlobIds(), storeGetOptions);
            MessageFormatSend blobsToSend =
                new MessageFormatSend(info.getMessageReadSet(), getRequest.getMessageFormatFlag(), messageFormatMetrics,
                    storeKeyFactory);
            PartitionResponseInfo partitionResponseInfo =
                new PartitionResponseInfo(partitionRequestInfo.getPartition(), info.getMessageReadSetInfo());
            messagesToSendList.add(blobsToSend);
            partitionResponseInfoList.add(partitionResponseInfo);
          } catch (StoreException e) {
            if (e.getErrorCode() == StoreErrorCodes.ID_Not_Found) {
              logger.trace("Store exception on a get with error code " + e.getErrorCode() + " " +
                  "for partition " + partitionRequestInfo.getPartition(), e);
              metrics.idNotFoundError.inc();
            } else if (e.getErrorCode() == StoreErrorCodes.TTL_Expired) {
              logger.trace("Store exception on a get with error code " + e.getErrorCode() + " " +
                  "for partition " + partitionRequestInfo.getPartition(), e);
              metrics.ttlExpiredError.inc();
            } else if (e.getErrorCode() == StoreErrorCodes.ID_Deleted) {
              logger.trace("Store exception on a get with error code " + e.getErrorCode() + " " +
                  "for partition " + partitionRequestInfo.getPartition(), e);
              metrics.idDeletedError.inc();
            } else {
              logger.error("Store exception on a get with error code " + e.getErrorCode() +
                  " for partition " + partitionRequestInfo.getPartition(), e);
              metrics.unExpectedStoreGetError.inc();
            }
            PartitionResponseInfo partitionResponseInfo = new PartitionResponseInfo(partitionRequestInfo.getPartition(),
                ErrorMapping.getStoreErrorMapping(e.getErrorCode()));
            partitionResponseInfoList.add(partitionResponseInfo);
          } catch (MessageFormatException e) {
            logger.error("Message format exception on a get with error code " + e.getErrorCode() +
                " for partitionRequestInfo " + partitionRequestInfo, e);
            if (e.getErrorCode() == MessageFormatErrorCodes.Data_Corrupt) {
              metrics.dataCorruptError.inc();
            } else if (e.getErrorCode() == MessageFormatErrorCodes.Unknown_Format_Version) {
              metrics.unknownFormatError.inc();
            }
            PartitionResponseInfo partitionResponseInfo = new PartitionResponseInfo(partitionRequestInfo.getPartition(),
                ErrorMapping.getMessageFormatErrorMapping(e.getErrorCode()));
            partitionResponseInfoList.add(partitionResponseInfo);
          }
        }
      }
      CompositeSend compositeSend = new CompositeSend(messagesToSendList);
      response = new GetResponse(getRequest.getCorrelationId(), getRequest.getClientId(), partitionResponseInfoList,
          compositeSend, ServerErrorCode.No_Error);
    } catch (Exception e) {
      logger.error("Unknown exception for request " + getRequest, e);
      response =
          new GetResponse(getRequest.getCorrelationId(), getRequest.getClientId(), ServerErrorCode.Unknown_Error);
    } finally {
      long processingTime = SystemTime.getInstance().milliseconds() - startTime;
      totalTimeSpent += processingTime;
      publicAccessLogger.info("{} {} processingTime {}", getRequest, response, processingTime);
      if (getRequest.getMessageFormatFlag() == MessageFormatFlags.Blob) {
        metrics.getBlobProcessingTimeInMs.update(processingTime);
      } else if (getRequest.getMessageFormatFlag() == MessageFormatFlags.BlobProperties) {
        metrics.getBlobPropertiesProcessingTimeInMs.update(processingTime);
      } else if (getRequest.getMessageFormatFlag() == MessageFormatFlags.BlobUserMetadata) {
        metrics.getBlobUserMetadataProcessingTimeInMs.update(processingTime);
      } else if (getRequest.getMessageFormatFlag() == MessageFormatFlags.All) {
        metrics.getBlobAllProcessingTimeInMs.update(processingTime);
      }
    }
    sendGetResponse(requestResponseChannel, response, request, responseQueueTimeMeasurement,
        responseSendTimeMeasurement, responseTotalTimeMeasurement, totalTimeSpent, response.sizeInBytes(),
        getRequest.getMessageFormatFlag(), metrics);
  }

  public void handleDeleteRequest(Request request)
      throws IOException, InterruptedException {
    DeleteRequest deleteRequest = DeleteRequest.readFrom(new DataInputStream(request.getInputStream()), clusterMap);
    long requestQueueTime = SystemTime.getInstance().milliseconds() - request.getStartTimeInMs();
    long totalTimeSpent = requestQueueTime;
    metrics.deleteBlobRequestQueueTimeInMs.update(requestQueueTime);
    metrics.deleteBlobRequestRate.mark();
    long startTime = SystemTime.getInstance().milliseconds();
    DeleteResponse response = null;
    try {
      ServerErrorCode error = validateRequest(deleteRequest.getBlobId().getPartition(), false);
      if (error != ServerErrorCode.No_Error) {
        logger.error("Validating delete request failed with error {} for request {}", error, deleteRequest);
        response = new DeleteResponse(deleteRequest.getCorrelationId(), deleteRequest.getClientId(), error);
      } else {
        MessageFormatInputStream stream = new DeleteMessageFormatInputStream(deleteRequest.getBlobId());
        MessageInfo info = new MessageInfo(deleteRequest.getBlobId(), stream.getSize());
        ArrayList<MessageInfo> infoList = new ArrayList<MessageInfo>();
        infoList.add(info);
        MessageFormatWriteSet writeset = new MessageFormatWriteSet(stream, infoList, false);
        Store storeToDelete = storeManager.getStore(deleteRequest.getBlobId().getPartition());
        storeToDelete.delete(writeset);
        response =
            new DeleteResponse(deleteRequest.getCorrelationId(), deleteRequest.getClientId(), ServerErrorCode.No_Error);
        if (notification != null) {
          notification
              .onBlobReplicaDeleted(currentNode.getHostname(), currentNode.getPort(), deleteRequest.getBlobId().getID(),
                  BlobReplicaSourceType.PRIMARY);
        }
      }
    } catch (StoreException e) {
      if (e.getErrorCode() == StoreErrorCodes.ID_Not_Found) {
        logger.trace("Store exception on a delete with error code " + e.getErrorCode() +
            " for request " + deleteRequest, e);
        metrics.idNotFoundError.inc();
      } else if (e.getErrorCode() == StoreErrorCodes.TTL_Expired) {
        logger.trace("Store exception on a delete with error code " + e.getErrorCode() +
            " for request " + deleteRequest, e);
        metrics.ttlExpiredError.inc();
      } else if (e.getErrorCode() == StoreErrorCodes.ID_Deleted) {
        logger.trace("Store exception on a delete with error code " + e.getErrorCode() +
            " for request " + deleteRequest, e);
        metrics.idDeletedError.inc();
      } else {
        logger.error("Store exception on a delete with error code " + e.getErrorCode() +
            " for request " + deleteRequest, e);
        metrics.unExpectedStoreDeleteError.inc();
      }
      response = new DeleteResponse(deleteRequest.getCorrelationId(), deleteRequest.getClientId(),
          ErrorMapping.getStoreErrorMapping(e.getErrorCode()));
    } catch (Exception e) {
      logger.error("Unknown exception for delete request " + deleteRequest, e);
      response = new DeleteResponse(deleteRequest.getCorrelationId(), deleteRequest.getClientId(),
          ServerErrorCode.Unknown_Error);
      metrics.unExpectedStoreDeleteError.inc();
    } finally {
      long processingTime = SystemTime.getInstance().milliseconds() - startTime;
      totalTimeSpent += processingTime;
      publicAccessLogger.info("{} {} processingTime {}", deleteRequest, response, processingTime);
      metrics.deleteBlobProcessingTimeInMs.update(processingTime);
    }
    requestResponseChannel.sendResponse(response, request,
        new NetworkRequestMetrics(new HistogramMeasurement(metrics.deleteBlobResponseQueueTimeInMs),
            new HistogramMeasurement(metrics.deleteBlobSendTimeInMs),
            new HistogramMeasurement(metrics.deleteBlobTotalTimeInMs), null, null, totalTimeSpent));
  }

  public void handleReplicaMetadataRequest(Request request)
      throws IOException, InterruptedException {
    ReplicaMetadataRequest replicaMetadataRequest =
        ReplicaMetadataRequest.readFrom(new DataInputStream(request.getInputStream()), clusterMap, findTokenFactory);
    long requestQueueTime = SystemTime.getInstance().milliseconds() - request.getStartTimeInMs();
    long totalTimeSpent = requestQueueTime;
    metrics.replicaMetadataRequestQueueTimeInMs.update(requestQueueTime);
    metrics.replicaMetadataRequestRate.mark();

    List<ReplicaMetadataRequestInfo> replicaMetadataRequestInfoList =
        replicaMetadataRequest.getReplicaMetadataRequestInfoList();
    int partitionCnt = replicaMetadataRequestInfoList.size();
    long startTimeInMs = SystemTime.getInstance().milliseconds();
    ReplicaMetadataResponse response = null;
    try {
      List<ReplicaMetadataResponseInfo> replicaMetadataResponseList =
          new ArrayList<ReplicaMetadataResponseInfo>(partitionCnt);
      for (ReplicaMetadataRequestInfo replicaMetadataRequestInfo : replicaMetadataRequestInfoList) {
        long partitionStartTimeInMs = SystemTime.getInstance().milliseconds();
        PartitionId partitionId = replicaMetadataRequestInfo.getPartitionId();
        ServerErrorCode error = validateRequest(partitionId, false);
        logger.trace("{} Time used to validate metadata request: {}", partitionId,
            (SystemTime.getInstance().milliseconds() - partitionStartTimeInMs));

        if (error != ServerErrorCode.No_Error) {
          logger.error("Validating replica metadata request failed with error {} for partition {}", error,
              partitionId);
          ReplicaMetadataResponseInfo replicaMetadataResponseInfo =
              new ReplicaMetadataResponseInfo(partitionId, error);
          replicaMetadataResponseList.add(replicaMetadataResponseInfo);
        } else {
          try {
            FindToken findToken = replicaMetadataRequestInfo.getToken();
            String hostName = replicaMetadataRequestInfo.getHostName();
            String replicaPath = replicaMetadataRequestInfo.getReplicaPath();
            Store store = storeManager.getStore(partitionId);

            partitionStartTimeInMs = SystemTime.getInstance().milliseconds();
            FindInfo findInfo =
                store.findEntriesSince(findToken, replicaMetadataRequest.getMaxTotalSizeOfEntriesInBytes());
            logger.trace("{} Time used to find entry since: {}", partitionId,
                (SystemTime.getInstance().milliseconds() - partitionStartTimeInMs));

            partitionStartTimeInMs = SystemTime.getInstance().milliseconds();
            replicationManager.updateTotalBytesReadByRemoteReplica(partitionId, hostName, replicaPath,
                findInfo.getFindToken().getBytesRead());
            logger.trace("{} Time used to update total bytes read: {}", partitionId,
                (SystemTime.getInstance().milliseconds() - partitionStartTimeInMs));

            partitionStartTimeInMs = SystemTime.getInstance().milliseconds();
            long remoteReplicaLagInBytes =
                replicationManager.getRemoteReplicaLagInBytes(partitionId, hostName, replicaPath);
            logger.trace("{} Time used to get remote replica lag in bytes: {}", partitionId,
                (SystemTime.getInstance().milliseconds() - partitionStartTimeInMs));

            ReplicaMetadataResponseInfo replicaMetadataResponseInfo =
                new ReplicaMetadataResponseInfo(partitionId, findInfo.getFindToken(), findInfo.getMessageEntries(),
                    remoteReplicaLagInBytes);
            replicaMetadataResponseList.add(replicaMetadataResponseInfo);
          } catch (StoreException e) {
            logger.error("Store exception on a replica metadata request with error code " + e.getErrorCode() +
                " for partition " + partitionId, e);
            if (e.getErrorCode() == StoreErrorCodes.IOError) {
              metrics.storeIOError.inc();
            } else {
              metrics.unExpectedStoreFindEntriesError.inc();
            }
            ReplicaMetadataResponseInfo replicaMetadataResponseInfo =
                new ReplicaMetadataResponseInfo(partitionId,
                    ErrorMapping.getStoreErrorMapping(e.getErrorCode()));
            replicaMetadataResponseList.add(replicaMetadataResponseInfo);
          }
        }
      }
      response =
          new ReplicaMetadataResponse(replicaMetadataRequest.getCorrelationId(), replicaMetadataRequest.getClientId(),
              ServerErrorCode.No_Error, replicaMetadataResponseList);
    } catch (Exception e) {
      logger.error("Unknown exception for request " + replicaMetadataRequest, e);
      response =
          new ReplicaMetadataResponse(replicaMetadataRequest.getCorrelationId(), replicaMetadataRequest.getClientId(),
              ServerErrorCode.Unknown_Error);
    } finally {
      long processingTime = SystemTime.getInstance().milliseconds() - startTimeInMs;
      totalTimeSpent += processingTime;
      publicAccessLogger.info("{} {} processingTime {}", replicaMetadataRequest, response, processingTime);
      logger.trace("{} {} processingTime {}", replicaMetadataRequest, response, processingTime);
      metrics.replicaMetadataRequestProcessingTimeInMs.update(processingTime);
    }

    requestResponseChannel.sendResponse(response, request,
        new NetworkRequestMetrics(new HistogramMeasurement(metrics.replicaMetadataResponseQueueTimeInMs),
            new HistogramMeasurement(metrics.replicaMetadataSendTimeInMs),
            new HistogramMeasurement(metrics.replicaMetadataTotalTimeInMs), null, null, totalTimeSpent));
  }

  private void sendPutResponse(RequestResponseChannel requestResponseChannel, PutResponse response, Request request,
      MetricsHistogram responseQueueTime, MetricsHistogram responseSendTime, MetricsHistogram requestTotalTime,
      long totalTimeSpent, long blobSize, ServerMetrics metrics)
      throws InterruptedException {
    if (response.getError() == ServerErrorCode.No_Error) {
      metrics.markPutBlobRequestRateBySize(blobSize);
      if (blobSize <= ServerMetrics.smallBlob) {
        requestResponseChannel.sendResponse(response, request,
            new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime,
                new HistogramMeasurement(metrics.putSmallBlobProcessingTimeInMs),
                new HistogramMeasurement(metrics.putSmallBlobTotalTimeInMs), totalTimeSpent));
      } else if (blobSize <= ServerMetrics.mediumBlob) {
        requestResponseChannel.sendResponse(response, request,
            new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime,
                new HistogramMeasurement(metrics.putMediumBlobProcessingTimeInMs),
                new HistogramMeasurement(metrics.putMediumBlobTotalTimeInMs), totalTimeSpent));
      } else {
        requestResponseChannel.sendResponse(response, request,
            new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime,
                new HistogramMeasurement(metrics.putLargeBlobProcessingTimeInMs),
                new HistogramMeasurement(metrics.putLargeBlobTotalTimeInMs), totalTimeSpent));
      }
    } else {
      requestResponseChannel.sendResponse(response, request,
          new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null, totalTimeSpent));
    }
  }

  private void sendGetResponse(RequestResponseChannel requestResponseChannel, GetResponse response, Request request,
      MetricsHistogram responseQueueTime, MetricsHistogram responseSendTime, MetricsHistogram requestTotalTime,
      long totalTimeSpent, long blobSize, MessageFormatFlags flags, ServerMetrics metrics)
      throws InterruptedException {

    if (blobSize <= ServerMetrics.smallBlob) {
      if (flags == MessageFormatFlags.Blob) {
        if (response.getError() == ServerErrorCode.No_Error) {
          metrics.markGetBlobRequestRateBySize(blobSize);
          requestResponseChannel.sendResponse(response, request,
              new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime,
                  new HistogramMeasurement(metrics.getSmallBlobProcessingTimeInMs),
                  new HistogramMeasurement(metrics.getSmallBlobTotalTimeInMs), totalTimeSpent));
        } else {
          requestResponseChannel.sendResponse(response, request,
              new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null,
                  totalTimeSpent));
        }
      } else {
        requestResponseChannel.sendResponse(response, request,
            new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null,
                totalTimeSpent));
      }
    } else if (blobSize <= ServerMetrics.mediumBlob) {
      if (flags == MessageFormatFlags.Blob) {
        if (response.getError() == ServerErrorCode.No_Error) {
          metrics.markGetBlobRequestRateBySize(blobSize);
          requestResponseChannel.sendResponse(response, request,
              new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime,
                  new HistogramMeasurement(metrics.getMediumBlobProcessingTimeInMs),
                  new HistogramMeasurement(metrics.getMediumBlobTotalTimeInMs), totalTimeSpent));
        } else {
          requestResponseChannel.sendResponse(response, request,
              new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null,
                  totalTimeSpent));
        }
      } else {
        requestResponseChannel.sendResponse(response, request,
            new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null,
                totalTimeSpent));
      }
    } else {
      if (flags == MessageFormatFlags.Blob) {
        if (response.getError() == ServerErrorCode.No_Error) {
          metrics.markGetBlobRequestRateBySize(blobSize);
          requestResponseChannel.sendResponse(response, request,
              new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime,
                  new HistogramMeasurement(metrics.getLargeBlobProcessingTimeInMs),
                  new HistogramMeasurement(metrics.getLargeBlobTotalTimeInMs), totalTimeSpent));
        } else {
          requestResponseChannel.sendResponse(response, request,
              new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null,
                  totalTimeSpent));
        }
      } else {
        requestResponseChannel.sendResponse(response, request,
            new NetworkRequestMetrics(responseQueueTime, responseSendTime, requestTotalTime, null, null,
                totalTimeSpent));
      }
    }
  }

  private ServerErrorCode validateRequest(PartitionId partition, boolean checkPartitionState) {
    // 1. check if partition exist on this node
    if (storeManager.getStore(partition) == null) {
      metrics.partitionUnknownError.inc();
      return ServerErrorCode.Partition_Unknown;
    }
    // 2. ensure the disk for the partition/replica is available
    List<ReplicaId> replicaIds = partition.getReplicaIds();
    for (ReplicaId replica : replicaIds) {
      if (replica.getDataNodeId().getHostname() == currentNode.getHostname()
          && replica.getDataNodeId().getPort() == currentNode.getPort()) {
        if (replica.getDiskId().getState() == HardwareState.UNAVAILABLE) {
          metrics.diskUnavailableError.inc();
          return ServerErrorCode.Disk_Unavailable;
        }
      }
    }
    // 3. ensure if the partition can be written to
    if (checkPartitionState && partition.getPartitionState() == PartitionState.READ_ONLY) {
      metrics.partitionReadOnlyError.inc();
      return ServerErrorCode.Partition_ReadOnly;
    }
    return ServerErrorCode.No_Error;
  }
}
