package cn.com.sinosure.mq.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Address;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;

public class SingleConnectionFactory extends ConnectionFactory {

	private enum State {
		/**
		 * The factory has never established a connection so far.
		 */
		NEVER_CONNECTED,
		/**
		 * The factory has established a connection in the past but the
		 * connection was lost and the factory is currently trying to
		 * reestablish the connection.
		 */
		CONNECTING,
		/**
		 * The factory has established a connection that is currently alive and
		 * that can be retrieved.
		 */
		CONNECTED,
		/**
		 * The factory and its underlying connection are closed and the factory
		 * cannot be used to retrieve new connections.
		 */
		CLOSED
	}

	private static final Logger LOGGER = LoggerFactory
			.getLogger(SingleConnectionFactory.class);

	public static final int CONNECTION_HEARTBEAT_IN_SEC = 10;
	public static final int CONNECTION_TIMEOUT_IN_MS = 1000;
	public static final int CONNECTION_ESTABLISH_INTERVAL_IN_MS = 500;

	ShutdownListener connectionShutdownListener;
	List<ConnectionListener> connectionListeners;
	volatile Connection connection;
	volatile State state = State.NEVER_CONNECTED;
	private ExecutorService executorService;

	private final Object operationOnConnectionMonitor = new Object();

	String vhost;
	String user;
	String password;

	private SingleConnectionFactory() {
		super();
		setRequestedHeartbeat(CONNECTION_HEARTBEAT_IN_SEC);
		setConnectionTimeout(CONNECTION_TIMEOUT_IN_MS);
		setClientProperties(buildClientProperty());
		connectionListeners = Collections
				.synchronizedList(new LinkedList<ConnectionListener>());
		connectionShutdownListener = new ConnectionShutDownListener();
	}

	public SingleConnectionFactory(String host, Integer port, String vhost,
			String user, String password) {
		this();
		super.setHost(host);
		super.setPort(port);
		this.vhost = vhost;
		this.user = user;
		this.password = password;
	}

	private Map<String,Object> buildClientProperty(){
		Map<String,Object> properties = new HashMap<String,Object>();
		InetAddress addr = null;
		try {
			addr = InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		properties.put("ip",addr == null ? "" : addr.getHostAddress().toString());
		properties.put("hostName",addr == null ? "" : addr.getHostName().toString());
		properties.put("instance", System.getProperty("weblogic.Name") == null ? ""	: System.getProperty("weblogic.Name"));
		return properties;
	}

	/**
	 * <p>
	 * Gets a new connection from the factory. As this factory only provides one
	 * connection for every process, the connection is established on the first
	 * call of this method. Every subsequent call will return the same instance
	 * of the first established connection.
	 * </p>
	 * 
	 * <p>
	 * In case a connection is lost, the factory will try to reestablish a new
	 * connection.
	 * </p>
	 * 
	 * @return The connection
	 */
	public Connection newConnection() throws IOException {
		// Throw an exception if there is an attempt to retrieve a connection
		// from a closed factory
		if (state == State.CLOSED) {
			throw new IOException(
					"Attempt to retrieve a connection from a closed connection factory");
		}
		// Try to establish a connection if there was no connection attempt so
		// far
		if (state == State.NEVER_CONNECTED) {
			establishConnection();
		}
		// Retrieve the connection if it is established
		if (connection != null && connection.isOpen()) {
			return connection;
		}
		// Throw an exception if no established connection could not be
		// retrieved
		LOGGER.error("Unable to retrieve connection");
		throw new IOException("Unable to retrieve connection");
	}

	/**
	 * <p>
	 * Closes the connection factory and interrupts all threads associated to
	 * it.
	 * </p>
	 * 
	 * <p>
	 * Note: Make sure to close the connection factory when not used any more as
	 * otherwise the connection may remain established and ghost threads may
	 * reside.
	 * </p>
	 */
	@PreDestroy
	public void close() {
		synchronized (operationOnConnectionMonitor) {
			if (state == State.CLOSED) {
				LOGGER.warn("Attempt to close connection factory which is already closed");
				return;
			}
			LOGGER.info("Closing connection factory");
			if (connection != null) {
				try {
					connection.close();
					connection = null;
				} catch (IOException e) {
					if (!connection.isOpen()) {
						LOGGER.warn("Attempt to close an already closed connection");
					} else {
						LOGGER.error("Unable to close current connection", e);
					}
				}
			}
			changeState(State.CLOSED);
			LOGGER.info("Closed connection factory");
		}
	}

	/**
	 * Registers a connection listener at the factory which is notified about
	 * changes of connection states.
	 * 
	 * @param connectionListener
	 *            The connection listener
	 */
	public void registerListener(ConnectionListener connectionListener) {
		connectionListeners.add(connectionListener);
	}

	/**
	 * Removes a connection listener from the factory.
	 * 
	 * @param connectionListener
	 *            The connection listener
	 */
	public void removeConnectionListener(ConnectionListener connectionListener) {
		connectionListeners.remove(connectionListener);
	}

	/**
	 * Sets an {@code ExecutorService} to be used for this connection. If none
	 * is set a default one will be used (currently 5 threads). Consuming of
	 * messages happens using this {@code ExecutorService}.
	 * <p/>
	 * Because we don't create a new connection to RabbitMQ every time
	 * {@link #newConnection()} is called changing the {@code ExecutorService}
	 * would only take effect when the underlying connection is closed.
	 * <p/>
	 * That is why the {@code ExecutorService} can only be set once. Every
	 * further invocation will result in an {@link IllegalStateException}.
	 * 
	 * @param executorService
	 *            to use for consuming messages
	 */
	public void setExecutorService(ExecutorService executorService) {
		if (this.executorService != null) {
			throw new IllegalStateException(
					"ExecutorService already set, trying to change it");
		}
		this.executorService = executorService;
	}

	/**
	 * @return the {@code ExecutorService} to use
	 */
	public ExecutorService getExecutorService() {
		return executorService;
	}

	/**
	 * Changes the factory state and notifies all connection listeners.
	 * 
	 * @param newState
	 *            The new connection factory state
	 */
	void changeState(State newState) {
		state = newState;
		notifyListenersOnStateChange();
	}

	/**
	 * Notifies all connection listener about a state change.
	 */
	void notifyListenersOnStateChange() {
		LOGGER.info("Notifying connection listeners about state change to {}",
				state);

		for (ConnectionListener listener : connectionListeners) {
			switch (state) {
			case CONNECTED:
				listener.onConnectionEstablished(connection);
				break;
			case CONNECTING:
				listener.onConnectionLost(connection);
				break;
			case CLOSED:
				listener.onConnectionClosed(connection);
				break;
			default:
				break;
			}
		}
	}

	/**
	 * Establishes a new connection.
	 * 
	 * @throws IOException
	 *             if establishing a new connection fails
	 */
	void establishConnection() throws IOException {
		synchronized (operationOnConnectionMonitor) {
			if (state == State.CLOSED) {
				throw new IOException(
						"Attempt to establish a connection with a closed connection factory");
			} else if (state == State.CONNECTED) {
				LOGGER.warn("Establishing new connection although a connection is already established");
			}
			try {

				LOGGER.info("Trying to set ACL ");
				this.setAcl();
				LOGGER.info("ACL Has seted :" + getVirtualHost());
				LOGGER.info("Trying to establish connection to {}:{}",
						getHost(), getPort());
				Address[] addrs;
				String hosts = getHost();
				String[] hostArray = null;
				if (hosts.contains(",")) {
					hostArray = hosts.split(",");
				}
				if (hostArray != null) {
					addrs = new Address[hostArray.length];
					for (int i = 0; i < hostArray.length; i++) {
						addrs[i] = new Address(hostArray[i], getPort());
					}
				} else {
					addrs = new Address[1];
					addrs[0] = new Address(hosts, getPort());
				}

				connection = super.newConnection(executorService, addrs);
				connection.addShutdownListener(connectionShutdownListener);
				LOGGER.info("Established connection to {}:{}", getHost(),
						getPort());
				changeState(State.CONNECTED);
			} catch (IOException e) {
				LOGGER.error("Failed to establish connection to {}:{}",
						getHost(), getPort());
				throw e;
			}
		}
	}

	void setAcl() {
		super.setVirtualHost(this.vhost);
		super.setUsername(this.user);
		super.setPassword(this.password);
	}

	/**
	 * A listener to register on the parent factory to be notified about
	 * connection shutdowns.
	 * 
	 * @author christian.bick
	 * 
	 */
	private class ConnectionShutDownListener implements ShutdownListener {

		@Override
		public void shutdownCompleted(ShutdownSignalException cause) {
			// Only hard error means loss of connection
			if (!cause.isHardError()) {
				return;
			}

			LOGGER.error("begin error:" + cause.getMessage());

			LOGGER.error("end error:");
			synchronized (operationOnConnectionMonitor) {
				// No action to be taken if factory is already closed
				// or already connecting
				if (state == State.CLOSED || state == State.CONNECTING) {
					return;
				}
				changeState(State.CONNECTING);
			}
			LOGGER.error("Connection to {}:{} lost", getHost(), getPort());
			while (state == State.CONNECTING) {
				try {
					establishConnection();
					return;
				} catch (IOException e) {
					LOGGER.info("Next reconnect attempt in {} ms",
							CONNECTION_ESTABLISH_INTERVAL_IN_MS);
					try {
						Thread.sleep(CONNECTION_ESTABLISH_INTERVAL_IN_MS);
					} catch (InterruptedException ie) {
						// that's fine, simply stop here
						return;
					}
				}
			}
		}
	}
}
