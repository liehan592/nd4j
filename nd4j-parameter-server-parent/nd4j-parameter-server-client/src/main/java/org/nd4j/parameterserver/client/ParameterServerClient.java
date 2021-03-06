package org.nd4j.parameterserver.client;

import com.mashape.unirest.http.Unirest;
import io.aeron.Aeron;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.nd4j.aeron.ipc.*;
import org.nd4j.aeron.ipc.response.HostPortPublisher;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.model.MasterStatus;
import org.nd4j.parameterserver.model.ServerTypeJson;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Parameter server
 * client for
 * publishing and
 * retrieving ndarrays
 *
 * pushNDArray will send the given ndarray to the send url.
 * This is used for updating the master's current state.
 *
 * getArray() is used for retrieving the master ndarray's current
 * state from the parameter server.
 *
 * @author Adam Gibson
 */
@Data
@AllArgsConstructor
@Builder
public class ParameterServerClient implements NDArrayCallback {
    //the url to send ndarrays to
    private String ndarraySendUrl;
    //the url to retrieve ndarrays from
    private String ndarrayRetrieveUrl;
    private AeronNDArraySubscriber subscriber;
    //host to listen on for the subscriber
    private String subscriberHost;
    //port to listen on for the subscriber
    private int subscriberPort;
    //the stream to listen on for the subscriber
    private int subscriberStream = 11;
    //the "current" ndarray
    private AtomicReference<INDArray> arr;
    private INDArray none = Nd4j.scalar(1.0);
    private AtomicBoolean running;
    private static Logger log = LoggerFactory.getLogger(ParameterServerClient.class);
    private String masterStatusHost;
    private int masterStatusPort;
    private ObjectMapper objectMapper = new ObjectMapper();
    private Aeron aeron;

    /**
     * Tracks number of
     * arrays send to responder.
     * @return
     */
    public int arraysSentToResponder() {
        if(objectMapper == null)
            objectMapper = new ObjectMapper();

        try {
            String type = objectMapper.readValue(Unirest.get(String.format("http://%s:%d/type",masterStatusHost,masterStatusPort))
                    .asJson().getBody().toString(), ServerTypeJson.class).getType();
            if(!type.equals("master"))
                throw new IllegalStateException("Wrong type " + type);
            Unirest.get(String.format("http://%s:%d/started",masterStatusHost,masterStatusPort)).asJson().getBody();
            return objectMapper.readValue(Unirest.get(String.format(
                    "http://%s:%d/started"
                    ,masterStatusHost,masterStatusPort))
                    .asJson()
                    .getBody()
                    .toString(),MasterStatus.class).getResponderN();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * Sends a post request to the
     * status server to determine if the master node is started.
     * @return
     */
    public boolean masterStarted() {
        if(objectMapper == null)
            objectMapper = new ObjectMapper();

        try {
            String type = objectMapper.readValue(Unirest.get(String.format("http://%s:%d/type",masterStatusHost,masterStatusPort))
                    .asJson().getBody().toString(), ServerTypeJson.class).getType();
            if(!type.equals("master"))
                throw new IllegalStateException("Wrong type " + type);
            Unirest.get(String.format("http://%s:%d/started",masterStatusHost,masterStatusPort)).asJson().getBody();
            return objectMapper.readValue(Unirest.get(String.format("http://%s:%d/started",masterStatusHost,masterStatusPort))
                    .asJson().getBody().toString(),MasterStatus.class).started();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }



    /**
     * Push an ndarray message to the specified
     * ndarray send url in the form of:
     * host;port:stream
     * where stream is the stream for connecting
     * to a listening aeron server
     * @param message the array to send
     */
    public void pushNDArrayMessage(NDArrayMessage message) {
        //start a subscriber that can send us ndarrays
        if(subscriber == null) {
            running = new AtomicBoolean(true);
            subscriber = AeronNDArraySubscriber.startSubscriber(
                    aeron,
                    subscriberHost,
                    subscriberPort,
                    this,
                    subscriberStream,running);
            log.debug("Started parameter server client on " + subscriber.connectionUrl());
        }

        String[] split = ndarraySendUrl.split(":");
        int port = Integer.parseInt(split[1]);
        int streamToPublish = Integer.parseInt(split[2]);
        String channel = AeronUtil.aeronChannel(split[0],port);
        log.debug("Parameter server client publishing to " + ndarraySendUrl);
        try(AeronNDArrayPublisher publisher = AeronNDArrayPublisher.builder()
                .streamId(streamToPublish)
                .aeron(aeron).channel(channel)
                .build()) {
            publisher.publish(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Push an ndarray to the specified
     * ndarray send url in the form of:
     * host;port:stream
     * where stream is the stream for connecting
     * to a listening aeron server
     * @param arr the array to send
     */
    public void pushNDArray(INDArray arr) {
        pushNDArrayMessage(NDArrayMessage.wholeArrayUpdate(arr));
    }


    /**
     * Get the connection url for the subscriber
     * in the format:
     * host:port:stream
     * @return the connection url for the subscriber
     * for this client
     */
    public String connectionUrl() {
        return AeronConnectionInformation.of(
                subscriberHost,
                subscriberPort,
                subscriberStream).toString();
    }



    /**
     *  Get an ndarray from the
     *  designated ndarray retrieve url.
     *  This will "pull" the current ndarray
     *  from the master
     * @return the current ndarray from the master.
     */
    public INDArray getArray() {
        //start a subscriber that can send us ndarrays
        if(subscriber == null) {
            running = new AtomicBoolean(true);
            subscriber = AeronNDArraySubscriber.startSubscriber(
                    aeron,
                    subscriberHost,
                    subscriberPort,
                    this,
                    subscriberStream,running);
            log.debug("Started parameter server client on " + subscriber.connectionUrl());
        }

        if(arr == null)
            arr = new AtomicReference<>(none);

        log.debug("Parameter server client retrieving url from " + ndarrayRetrieveUrl);
        //note here that this is the "master url"
        String[] split = ndarrayRetrieveUrl.split(":");
        //The response daemon is always the master daemon's port + 1
        //A "master daemon" is one that holds both the
        //parameter averaging daemon AND the response daemon for being able to send
        //the "current state ndarray"
        int port = Integer.parseInt(split[1]);
        int streamToPublish = Integer.parseInt(split[2]);
        //the channel here is the master node host with the port + 1
        //pointing at the response node where we can request ndarrays to be sent to
        //the listening daemon
        String channel = AeronUtil.aeronChannel(split[0], port);
        //publish the address of our subscriber
        //note here that we send the ndarray send url, because the
        //master also hosts
        try (HostPortPublisher hostPortPublisher = HostPortPublisher
                .builder().channel(channel).aeron(aeron)
                //note here that we send our subscriber's listening information
                .streamId(streamToPublish)
                .uriToSend(AeronConnectionInformation.of(subscriberHost, subscriberPort, subscriberStream).toString())
                .build()) {
            hostPortPublisher.send();


            log.debug("Sent subscriber information " + AeronConnectionInformation.of(subscriberHost, subscriberPort, subscriberStream).toString());

            //wait for array to be available
            while (arr.get() == none) {
                Thread.sleep(1000);
                hostPortPublisher.send();
                log.info("Waiting on array to be updated.");
            }

        }
        catch (Exception e) {
            log.error("Error with publishing", e);
        }


        INDArray arr2 = arr.get();
        arr.set(none);
        return arr2;
    }

    /**
     * Used for partial updates using tensor along
     * dimension
     *  @param arr        the array to count as an update
     * @param idx        the index for the tensor along dimension
     * @param dimensions the dimensions to act on for the tensor along dimension
     */
    @Override
    public void onNDArrayPartial(INDArray arr, long idx, int... dimensions) {
        INDArray get = this.arr.get();
        get.tensorAlongDimension((int) idx,dimensions).assign(arr);

    }

    /**
     * Setup an ndarray
     *
     * @param arr
     */
    @Override
    public  void onNDArray(INDArray arr) {
        this.arr.set(arr);
    }
}
