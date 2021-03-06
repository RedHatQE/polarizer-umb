package com.github.redhatqe.polarizer.messagebus;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.redhatqe.polarizer.messagebus.config.Broker;
import com.github.redhatqe.polarizer.messagebus.config.BrokerConfig;
import com.github.redhatqe.polarizer.messagebus.exceptions.NoConfigFoundError;
import com.github.redhatqe.polarizer.messagebus.utils.Tuple;
import com.github.redhatqe.polarizer.reporter.configuration.Serializer;
import com.github.redhatqe.polarizer.reporter.utils.JsonHelper;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.commons.collections4.queue.CircularFifoQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import javax.jms.Queue;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * A Class that provides functionality to listen to the CI Message Bus
 */
public class CIBusListener<T> extends CIBusClient implements ICIBus, IMessageListener {
    static public Logger logger = LoggerFactory.getLogger(CIBusListener.class.getName());
    private String topic;
    private Subject<ObjectNode> nodeSub;
    private Subject<MessageResult<T>> resultSubject;
    private Integer messageCount = 0;
    public CircularFifoQueue<MessageResult<T>> messages;
    private static final Integer SUBJECT_COMPLETED = -1;
    private Connection connection = null;


    public CIBusListener() {
        this(IMessageListener.defaultHandler(), ICIBus.getDefaultConfigPath());
    }

    public CIBusListener(MessageHandler<T> hdlr) {
        this(hdlr, ICIBus.getDefaultConfigPath());
    }

    public CIBusListener(MessageHandler<T> hdlr, String path) {
        super();
        this.topic = TOPIC;
        this.uuid = UUID.randomUUID();
        this.clientID = POLARIZE_CLIENT_ID + "." + this.uuid;
        this.configPath = path;
        this.brokerConfig = ICIBus
                .getConfigFromPath(BrokerConfig.class, this.configPath)
                .orElseThrow(() -> new NoConfigFoundError(String.format("Could not find configuration file at %s", this.configPath)));
        this.broker = this.brokerConfig.getBrokers().get(this.brokerConfig.getDefaultBroker());
        this.messages = new CircularFifoQueue<>(20);
        this.resultSubject = this.setupResultSubject();
        this.nodeSub = this.setupDefaultSubject(hdlr);
    }

    public CIBusListener(MessageHandler<T> hdlr, BrokerConfig cfg) {
        super();
        this.topic = TOPIC;
        this.uuid = UUID.randomUUID();
        this.clientID = POLARIZE_CLIENT_ID + "." + this.uuid;
        this.configPath = "";
        if (cfg != null)
            this.brokerConfig = cfg;
        else
            throw new NoConfigFoundError("BrokerConfig can't be null");
        this.broker = this.brokerConfig.getBrokers().get(this.brokerConfig.getDefaultBroker());
        this.messages = new CircularFifoQueue<>(20);
        this.resultSubject = this.setupResultSubject();
        this.nodeSub = this.setupDefaultSubject(hdlr);
    }

    public Subject<ObjectNode> getNodeSub() {
        return this.nodeSub;
    }

    public Subject<MessageResult<T>> getResultSubject() {
        return resultSubject;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessages(Integer messageCount) {
        this.messageCount = messageCount;
        CircularFifoQueue<MessageResult<T>> fifo = new CircularFifoQueue<>(messageCount);
        fifo.addAll(this.messages);
        this.messages = fifo;
    }

    public String getClientID() { return this.clientID; }

    /**
     * Creates a Subject with a default set of onNext, onError, and onComplete handlers
     *
     * @param handler A MessageHandler that will be applied by the subscriber
     * @return A Subject which will pass the Object node along
     */
    private Subject<ObjectNode> setupDefaultSubject(MessageHandler<T> handler) {
        // handler for onNext
        Consumer<ObjectNode> next = (ObjectNode node) -> {
            MessageResult<T> result = handler.handle(node);
            logger.info("Got a message");
            // FIXME: I dont like storing state like this, but onNext doesn't return anything
            this.messageCount++;
            this.messages.add(result);
            this.resultSubject.onNext(result);
        };
        // handler for onComplete
        Action act = () -> {
            logger.info("Stop listening!");
            this.messageCount = SUBJECT_COMPLETED;
        };
        // FIXME: use DI to figure out what kind of Subject to create, ie AsyncSubject, BehaviorSubject, etc
        Subject<ObjectNode> n = BehaviorSubject.create();
        n.subscribe(next, Throwable::printStackTrace, act);
        return n;
    }

    private Subject<MessageResult<T>>
    setupResultSubject() {
        ObjectMapper mapper = new ObjectMapper();
        Consumer<MessageResult<T>> next = (n) -> {
            n.getNode().ifPresent(node -> {
                JsonNode root = node.get("root");
                try {
                    Object text = mapper.treeToValue(root, Object.class);
                    logger.info(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(text));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        };
        Action act = () -> {
            logger.info("resultSubject stopped listening");
        };
        Subject<MessageResult<T>> subj = PublishSubject.create();
        subj.subscribe(next, Throwable::printStackTrace, act);
        return subj;
    }

    /**
     * Creates a default listener for MapMessage types
     *
     * @param parser a MessageParser lambda that will be applied to the MessageListener
     * @return a MessageListener lambda
     */
    @Override
    public MessageListener createListener(MessageParser parser) {
        return msg -> {
            try {
                ObjectNode node = parser.parse(msg);
                // Since nodeSub is a Subject, the call to onNext will pass through the node object to itself
                this.nodeSub.onNext(node);
            } catch (ExecutionException | InterruptedException | JMSException e) {
                this.nodeSub.onError(e);
            }
        };
    }

    /**
     * A synchronous blocking call to receive a message from the message bus
     *
     * @param selector the JMS selector to get a message from a topic
     * @return An optional tuple of the session connection and the Message object
     */
    @Override
    public Optional<Tuple<Connection, Message>> waitForMessage(String selector) {
        String brokerUrl = this.broker.getUrl();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        Connection connection;
        MessageConsumer consumer;
        Message msg;

        try {
            String user = this.broker.getUser();
            String pw = this.broker.getPassword();
            factory.setUserName(user);
            factory.setPassword(pw);
            connection = factory.createConnection();
            connection.setClientID(this.clientID);
            connection.setExceptionListener(exc -> logger.error(exc.getMessage()));

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic dest = session.createTopic(this.topic);

            if (selector == null || selector.equals(""))
                throw new Error("Must supply a value for the selector");

            logger.debug(String.format("Using selector of:\n%s", selector));
            connection.start();
            consumer = session.createConsumer(dest, selector);
            String timeout = this.broker.getMessageTimeout().toString();
            msg = consumer.receive(Integer.parseInt(timeout));

        } catch (JMSException e) {
            e.printStackTrace();
            return Optional.empty();
        }
        Tuple<Connection, Message> tuple = new Tuple<>(connection, msg);
        return Optional.of(tuple);
    }


    /**
     * An asynchronous way to get a Message with a MessageListener
     *
     * @param selector String to use for JMS selector
     * @param listener a MessageListener to be passed to the Session
     * @return an Optional Connection to be used for closing the session
     */
    @Override
    public Optional<Connection>
    tapIntoMessageBus( String selector
                     , MessageListener listener
                     , String publishDest) {
        if (this.connection != null) {
            logger.info("This CIBusListener already being used.  Create another CIBusListner object");
            return Optional.of(this.connection);
        }
        String brokerUrl = this.broker.getUrl();
        ActiveMQConnectionFactory factory = this.setupFactory(brokerUrl, this.broker);
        Connection connection = null;
        MessageConsumer consumer;
        logger.info(String.format("In CIBusListener: Using selector of %s", selector));

        try {
            connection = factory.createConnection();
            connection.setClientID(this.clientID);
            connection.setExceptionListener(exc -> logger.error(exc.getMessage()));

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue dest = session.createQueue(publishDest);
            if (selector.equals(""))
                consumer = session.createConsumer(dest);
            else
                consumer = session.createConsumer(dest, selector);

            // FIXME: We need to have some way to know when we see our message.
            consumer.setMessageListener(listener);
            connection.start();
        } catch (JMSException e) {
            e.printStackTrace();
        } catch (Exception e) {
            logger.error("Error getting keystore");
            e.printStackTrace();
        }
        this.connection = connection;
        return Optional.ofNullable(connection);
    }

    public MessageParser messageParser() {
        return this::parseMessage;
    }

    /**
     * Parses a Message returning a Jackson ObjectNode
     *
     * @param msg Message received from a Message bus
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws JMSException
     */
    @Override
    public ObjectNode parseMessage(Message msg) throws JMSException  {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        if (msg instanceof MapMessage) {
            MapMessage mm = (MapMessage) msg;
            Enumeration names = mm.getMapNames();
            while(names.hasMoreElements()) {
                String p = (String) names.nextElement();
                String field = mm.getStringProperty(p);
                root.set(field, mapper.convertValue(mm.getObject(field), JsonNode.class));
            }
            return root;
        }
        else if (msg instanceof TextMessage) {
            TextMessage tm = (TextMessage) msg;
            Enumeration props = tm.getPropertyNames();
            while(props.hasMoreElements()) {
                String p = props.nextElement().toString();
                if (p.equals("type")) {
                    String val = msg.getStringProperty("type");
                    logger.info(String.format("Message prop: type=%s", val));
                }
                else if (p.equals("rhsm_qe")) {
                    String val = msg.getStringProperty("rhsm_qe");
                    logger.info(String.format("Message prop: rhsm_qe=%s", val));
                }
                else if (p.equals("job-id")) {
                    String val = msg.getStringProperty("job-id");
                    logger.info(String.format("Message prop: job-id=%s", val));
                }
            }
            String text = tm.getText();
            logger.info(text);
            try {
                JsonNode node = mapper.readTree(text);
                root.set("root", node);  // FIXME: this is hacky
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else {
            String err = msg == null ? " was null" : msg.toString();
            logger.error(String.format("Unknown Message:  Could not read message %s", err));
        }
        return root;
    }

    /**
     * Overrides the broker's timeout value with the given timeout and count
     *
     * Loop will stop once either the timeout has expired or the number of messages of reached is received
     *
     * @param timeout number of milliseconds to wait
     * @param count number of
     */
    public void listenUntil(Long timeout, Integer count) {
        Long start = Instant.now().getEpochSecond();
        Long end = start + (timeout / 1000);
        Instant endtime = Instant.ofEpochSecond(end);
        int mod = 0;
        logger.info("Begin listening for message.  Times out at " + endtime.toString());
        while(true) {
            if (this.messageCount >= count || Instant.now().getEpochSecond() > end)
                break;
            try {
                Thread.sleep(1000);
                mod++;
                String msg = "Current msg count = %d. Waiting on message for %d seconds...";
                if (mod % 10 == 0)
                    logger.info(String.format(msg, this.messageCount, mod));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        this.nodeSub.onComplete();
    }

    public void listenUntil() {
        this.listenUntil(this.broker.getMessageTimeout(), this.broker.getMessageMax());
    }

    /**
     * Overrides the broker's default message timeout
     *
     * @param timeout number of milliseconds before timing out
     */
    public void listenUntil(Long timeout) {
        this.listenUntil(timeout, this.broker.getMessageMax());
    }

    /**
     * Overrides the broker's default message max
     *
     * @param count number of messages to get before quitting
     */
    public void listenUntil(Integer count) {
        this.listenUntil(this.broker.getMessageTimeout(), count);
    }

    /**
     * Does 2 things: launches waitForMessage from a Fork/Join pool thread and the main thread waits for user to quit
     *
     * Takes one argument: a string that will be used as the JMS Selector
     *
     * @param args
     */
    public static void main2(String[] args) throws ExecutionException, InterruptedException, JMSException {
        // FIXME: Use guice to make something that is an IMessageListener so we can mock it out
        CIBusListener<DefaultResult> bl = new CIBusListener<>();

        Broker b = bl.brokerConfig.getBrokers().get("ci");
        b.setMessageMax(1);
        CIBusPublisher cbp = new CIBusPublisher(bl.brokerConfig);
        String body = "{ \"testing\": \"Hello World\"}";
        Map<String, String> props = new HashMap<>();
        props.put("rhsm_qe", "polarize_bus");

        String sel = "rhsm_qe='xunit_importer'";
        String publishDest = String.format("Consumer.%s.%s", bl.clientID, TOPIC);
        Optional<Connection> rconn = bl.tapIntoMessageBus(sel, bl.createListener(bl.messageParser()), publishDest);
        //Thread.sleep(10000);
        Optional<Connection> sconn = cbp.sendMessage(body, b, new JMSMessageOptions("stoner-polarize", props));

        bl.listenUntil(10);
        MessageResult<DefaultResult> result = bl.messages.remove();
        if (result.getNode().isPresent()) {
            ObjectNode node = result.getNode().get();
            ObjectMapper mapper = new ObjectMapper();
            try {
                JsonNode testNode = mapper.readTree(body);
                String expected = testNode.get("testing").textValue();
                bl.logger.info("Testing value was " + expected);
                //JsonNode testing = node.get("root");
                //String actual = testing.get("testing").textValue();
            } catch (IOException e) {
                bl.logger.error("Invalid Test: The expected value in the test did not convert to a Json object");
            }
        }
        else
            bl.logger.error("No message node");

        rconn.ifPresent((Connection c) -> {
            try {
                bl.logger.info("Closing the receiver connection");
                c.close();
            } catch (JMSException e) {
                e.printStackTrace();
            }
        });

        sconn.ifPresent(c -> {
            try {
                bl.logger.info("Closing the sender connection");
                c.close();
            } catch (JMSException e) {
                e.printStackTrace();
            }
        });
    }

    public Connection getConnection() {
        return connection;
    }

    public static MessageHandler<DefaultResult> xunitMsgHandler() {
        return (ObjectNode node) -> {
            JsonNode root = node.get("root");
            MessageResult<DefaultResult> result = new MessageResult<>(node);
            result.info = new DefaultResult();

            try {
                Boolean passed = root.get("status").textValue().equals("passed");
                if (passed) {
                    logger.info("In xunitMsgHandler: XUnit importer was successful");
                    String testrunUrl = root.get("testrun-url").textValue();
                    logger.info(String.format("Polarion TestRun = %s", testrunUrl));
                    result.info.setText(JsonHelper.nodeToString(root));
                    result.setStatus(MessageResult.Status.SUCCESS);
                }
                else {
                    // Figure out which one failed
                    if (root.has("import-results")) {
                        JsonNode results = root.get("import-results");
                        List<String> suites = new ArrayList<>();
                        results.elements().forEachRemaining(element -> {
                            if (element.has("status") && !element.get("status").textValue().equals("passed")) {
                                if (element.has("suite-name")) {
                                    String suite = element.get("suite-name").textValue();
                                    suites.add(suite);
                                    logger.info(suite + " failed to be updated");
                                }
                            }
                        });
                        result.setStatus(MessageResult.Status.FAILED);
                        result.setErrorDetails("TestSuites failed to be updated: " + String.join(",", suites));
                    }
                    else {
                        logger.error(root.get("message").asText());
                        result.setStatus(MessageResult.Status.EMPTY_MESSAGE);
                        result.setErrorDetails(root.get("message").toString());
                    }
                }
            } catch (NullPointerException npe) {
                String err = "Unknown format of message from bus";
                logger.error(err);
                result.setStatus(MessageResult.Status.NP_EXCEPTION);
                result.setErrorDetails(err);
            } catch (JsonProcessingException e) {
                String err = "Unable to deserialize JsonNode";
                logger.error(err);
                result.setStatus(MessageResult.Status.WRONG_MESSAGE_FORMAT);
                result.setErrorDetails(err);
                e.printStackTrace();
            }
            return result;
        };
    }


    /**
     * Does 2 things: launches waitForMessage from a Fork/Join pool thread and the main thread waits for user to quit
     *
     * Takes one argument: a string that will be used as the JMS Selector
     *
     * @param args
     */
    public static void main(String[] args) throws ExecutionException, InterruptedException, JMSException, IOException {
        // FIXME: Use guice to make something that is an IMessageListener so we can mock it out
        String defaultBrokerPath = ICIBus.getDefaultConfigPath();
        BrokerConfig brokerCfg = Serializer.fromYaml(BrokerConfig.class, new File(defaultBrokerPath));

        //CIBusListener<DefaultResult> bl = new CIBusListener<>(xunitMsgHandler(), brokerCfg);
        CIBusListener<DefaultResult> bl = new CIBusListener<>();

        Broker b = bl.brokerConfig.getBrokers().get("ci");
        b.setMessageMax(100);

        //Map<String, String> props = new HashMap<>();
        //props.put(args[0], args[1]);

        //String sel = String.format("%s='%s'", args[0], args[1]);
        String sel = "rhsm_qe='testcase_importer'";
        String publishDest = String.format("Consumer.%s.%s", bl.clientID, TOPIC);
        logger.info(String.format("Topic = %s", publishDest));
        Optional<Connection> rconn = bl.tapIntoMessageBus(sel, bl.createListener(bl.messageParser()), publishDest);
        bl.getResultSubject().subscribe(n -> {
            logger.info(n.getBody());
        });

        bl.listenUntil(1);
        //bl.listenUntil(1);
        if (!bl.messages.isEmpty()) {
            MessageResult<DefaultResult> result = bl.messages.remove();
            if (result.getNode().isPresent()) {
                ObjectNode node = result.getNode().get();
                ObjectMapper mapper = new ObjectMapper();
                JsonNode testing = node.get("root");
                //bl.logger.info(testing.asText());
            } else
                logger.error("No message node");
        }

        rconn.ifPresent((Connection c) -> {
            try {
                logger.info("Closing the receiver connection");
                c.close();
            } catch (JMSException e) {
                e.printStackTrace();
            }
        });
    }
}
