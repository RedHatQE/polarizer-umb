# What is polarizer-umb?

This is a small JMS/ActiveMQ helper library specifically designed for an ActiveMQ broker using a TLS cert for authentication and 
encryption.

## What can I use it for?

The polarizer-vertx project makes use of this in it's UMB (Unified Message Bus) verticle.  It uses this library's asynchronous mode
to listen for objects, by bridging the MessageListener with an rxjava Observable.

## Why did I make it?

- Because not every QE team uses python
- Because having a Jenkins plugin that listens for messages to kick off a job based on JMS message is not the only use case

While the redhat-ci-jenkins plugin is definitely useful, it's also not flexible.  Ideally, a higher level library
should have been created and the jenkins plugin would have been written to use this.  In other words, the plugin would
have just been another client making use of the library.  Had this strategy been followed, other teams with other 
requirements (for example, not testing on jenkins or with other workflows) could have made use of it too. 

Currently, if another application wants to make use of this library, they would need to follow the directions for setting up their
own TLS cert and getting the appropriate permissions.  This is not needed if they only wish to make use of listening for messages on 
the UMB that are published from the following queue Destination:


## Configuration and Requirements

The JMS broker we are using requires client side TLS authentication.  If you have a Broker that requires this, you need to set up
the broker-config.yml file:

```yaml

brokers:
  ci:
    url: "failover:(ssl://your.broker1:12345,ssl://your.broker2:12345)"
    user: foo
    password: blah
    messages:
      timeout: 300000
      maxMsgs: 2
    tls:
      keystore-path: "/path/to/your/keystore.jks"  # Path to the .jks keystore
      truststore-path: "/path/to/your/truststore.jks"       # Path to the .jks truststore
      keystorekey-pw: "pw-of-pvtkey-tokeystore"             # Password of the private key (from the .p12 file)
      keystore-pw: "pw-of-keystore"                         # Password of the keystore file (jks)
      truststore-pw: "pw-of-truststore"                     # Password of the truststore file
  metrics:
    url: "another.server.com:65432"
    user: "foo"
    password: "bar"
    messages:
      timeout: 1000
      maxMsgs: 1
defaultBroker: ci
```

Here, the brokers are keyed by name (eg, 'ci' and 'metrics').  If your broker requires client side TLS, then you can use the tls 
section to store the required keystores and truststores (sorry, no PEM or CRT formats).

The messages section has a timeout and maxMsgs.  These are used to specify max times if you are using the CIBusListener's listen() 
methods.  The listen() methods are essentially blocking loops that will wait that long for messages to arrive before timing out.
The maxMsgs is the number of messages to receive before exiting the loop.

## How to build it **FIXME**  

Right now, due to the way the build.gradle and artifactory plugin works, a builder needs access to artifactory for this project.
This is too restrictive, so another method of building this will need to be figured out.

Do one of the following:

- Remove the need for artifactory since it is too complicated and requires credentials
  - ie, just use maven central
- Remove just the artifactory credentials requirement (don't allow publishing)
