package io.github.leovr.rtipmidi.session;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;

import io.github.leovr.rtipmidi.AppleMidiCommandListener;
import io.github.leovr.rtipmidi.AppleMidiMessageListener;
import io.github.leovr.rtipmidi.error.AppleMidiSessionServerRuntimeException;
import io.github.leovr.rtipmidi.handler.AppleMidiCommandHandler;
import io.github.leovr.rtipmidi.handler.AppleMidiMessageHandler;
import io.github.leovr.rtipmidi.messages.AppleMidiClockSynchronization;
import io.github.leovr.rtipmidi.messages.AppleMidiCommand;
import io.github.leovr.rtipmidi.messages.AppleMidiEndSession;
import io.github.leovr.rtipmidi.messages.AppleMidiInvitation;
import io.github.leovr.rtipmidi.messages.AppleMidiInvitationAccepted;
import io.github.leovr.rtipmidi.messages.AppleMidiInvitationDeclined;
import io.github.leovr.rtipmidi.messages.AppleMidiInvitationRequest;
import io.github.leovr.rtipmidi.messages.AppleMidiMessage;
import io.github.leovr.rtipmidi.messages.MidiCommandHeader;
import io.github.leovr.rtipmidi.messages.RtpHeader;
import io.github.leovr.rtipmidi.model.AppleMidiServer;
import io.github.leovr.rtipmidi.model.MidiMessage;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * The session server handles MIDI invitation, clock synchronin requests, as well as the MIDI messages. In order to handle MIDI messages a {@link AppleMidiSession} has to be added via
 * {@link #addAppleMidiSession(AppleMidiSession)}. Otherwise all invitation requests are rejected. The session server must be run on a port which is {@code control port + 1}
 */
@Slf4j
public class AppleMidiSessionServer implements AppleMidiCommandListener, AppleMidiMessageListener, Runnable {

	private static final int SOCKET_TIMEOUT = 1000;

	private enum State {
		ACCEPT_INVITATIONS, FULL
	}

	@Value
	private static class AppleMidiSessionAppleMidiServer {

		@Nonnull
		private AppleMidiSession appleMidiSession;
		@Nonnull
		private AppleMidiServer appleMidiServer;
	}

	private static final int RECEIVE_BUFFER_LENGTH = 1024;

	private final ExecutorService executorService;
	private final int ssrc;
	private final String name;
	private final AppleMidiCommandHandler midiCommandHandler = new AppleMidiCommandHandler();
	private final AppleMidiMessageHandler midiMessageHandler = new AppleMidiMessageHandler();
	private final int port;
	private boolean running = true;
	private DatagramSocket socket;
	private final Deque<AppleMidiSession> sessions = new ArrayDeque<>();
	private final Map<Integer, AppleMidiSessionAppleMidiServer> currentSessions = new HashMap<>();
	private final List<SessionChangeListener> sessionChangeListeners = new ArrayList<>();
	private Thread thread;
	private short sequenceNumber = 0;

	/**
	 * @param name The name under which the other peers should see this server
	 * @param port The session server port which must be {@code control port + 1}
	 */
	public AppleMidiSessionServer(@Nonnull final String name, final int port) {
		this(name, port, Executors.newCachedThreadPool());
	}

	AppleMidiSessionServer(@Nonnull final String name, final int port, final ExecutorService executorService) {
		this.port = port;
		this.ssrc = new Random().nextInt();
		this.name = name;
		this.executorService = executorService;

		midiCommandHandler.registerListener(this);
		midiMessageHandler.registerListener(this);
	}

	Thread createThread(final @Nonnull String name) {
		return new Thread(this, name + "SessionThread");
	}

	public synchronized void start() {
		thread = createThread(name);
		try {
			socket = createSocket();
			socket.setSoTimeout(SOCKET_TIMEOUT);
		} catch (final SocketException e) {
			throw new AppleMidiSessionServerRuntimeException("DatagramSocket cannot be opened", e);
		}
		thread.start();
		log.debug("MIDI session server started");
	}

	DatagramSocket createSocket() throws SocketException {
		return new DatagramSocket(port);
	}

	@Override
	public void run() {
		while (running) {

			try {
				final byte[] receiveData = new byte[RECEIVE_BUFFER_LENGTH];
				final DatagramPacket incomingPacket = new DatagramPacket(receiveData, receiveData.length);
				socket.receive(incomingPacket);
				executorService.execute(new Runnable() {
					@Override
					public void run() {
						if (receiveData[0] == AppleMidiCommand.MIDI_COMMAND_HEADER1) {
							midiCommandHandler.handle(receiveData, new AppleMidiServer(incomingPacket.getAddress(), incomingPacket.getPort()));
						} else {
							midiMessageHandler.handle(receiveData, new AppleMidiServer(incomingPacket.getAddress(), incomingPacket.getPort()));
						}
					}
				});
			} catch (final SocketTimeoutException ignored) {
			} catch (final IOException e) {
				log.error("IOException while receiving", e);
			}
		}
		socket.close();
	}

	/**
	 * Shutds down all sockets and threads
	 */
	public void stopServer() {
		running = false;
		currentSessions.clear();
		sessions.clear();
		executorService.shutdown();
		log.debug("MIDI session server stopped");
	}

	private void send(final AppleMidiCommand midiCommand, final AppleMidiServer appleMidiServer) throws IOException {
		final byte[] invitationAcceptedBytes = midiCommand.toByteArray();
		socket.send(new DatagramPacket(invitationAcceptedBytes, invitationAcceptedBytes.length, appleMidiServer.getInetAddress(), appleMidiServer.getPort()));
	}

	@Override
	public void onMidiInvitation(@Nonnull final AppleMidiInvitationRequest invitation, @Nonnull final AppleMidiServer appleMidiServer) {
		log.info("MIDI invitation from: {}", appleMidiServer);
		if (getSessionServerState() == State.ACCEPT_INVITATIONS) {
			sendMidiInvitationAnswer(appleMidiServer, "accept", new AppleMidiInvitationAccepted(invitation.getProtocolVersion(), invitation.getInitiatorToken(), ssrc, name));
			currentSessions.put(invitation.getSsrc(), new AppleMidiSessionAppleMidiServer(sessions.pop(), appleMidiServer));
			notifyMaxNumberOfSessions();
		} else {
			sendMidiInvitationAnswer(appleMidiServer, "decline", new AppleMidiInvitationDeclined(invitation.getProtocolVersion(), invitation.getInitiatorToken(), ssrc, name));
		}
	}

	/**
	 * @return {@link State#FULL} if no sessions are available. {@link State#ACCEPT_INVITATIONS} otherwise
	 */
	private State getSessionServerState() {
		return sessions.isEmpty() ? State.FULL : State.ACCEPT_INVITATIONS;
	}

	private void sendMidiInvitationAnswer(final AppleMidiServer appleMidiServer, final String type, final AppleMidiInvitation midiInvitation) {
		try {
			log.info("Sending invitation {} to: {}", type, appleMidiServer);
			send(midiInvitation, appleMidiServer);
		} catch (final IOException e) {
			log.error("IOException while sending invitation {}", type, e);
		}
	}

	@Override
	public void onClockSynchronization(@Nonnull final AppleMidiClockSynchronization clockSynchronization, @Nonnull final AppleMidiServer appleMidiServer) {
		if (clockSynchronization.getCount() == (byte) 0) {
			final AppleMidiSessionAppleMidiServer sessionTuple = currentSessions.get(clockSynchronization.getSsrc());
			final long currentTimestamp;
			if (sessionTuple != null && sessionTuple.getAppleMidiSession().getCurrentTimestamp() != -1) {
				currentTimestamp = sessionTuple.getAppleMidiSession().getCurrentTimestamp();
			} else {
				currentTimestamp = ManagementFactory.getRuntimeMXBean().getUptime() * 10;
			}
			final AppleMidiClockSynchronization clockSynchronizationAnswer = new AppleMidiClockSynchronization(ssrc, (byte) 1, clockSynchronization.getTimestamp1(), currentTimestamp, 0L);
			try {
				send(clockSynchronizationAnswer, appleMidiServer);
			} catch (final IOException e) {
				log.error("IOException while sending clock synchronization", e);
			}
		} else if (clockSynchronization.getCount() == (byte) 2) {
			final long offsetEstimate = (clockSynchronization.getTimestamp3() + clockSynchronization.getTimestamp1()) / 2 - clockSynchronization.getTimestamp2();
			final AppleMidiSessionAppleMidiServer midiServer = currentSessions.get(clockSynchronization.getSsrc());
			if (midiServer != null) {
				midiServer.getAppleMidiSession().setOffsetEstimate(offsetEstimate);
			}
		}
	}

	@Override
	public void onEndSession(@Nonnull final AppleMidiEndSession appleMidiEndSession, @Nonnull final AppleMidiServer appleMidiServer) {
		log.info("Session end from: {}", appleMidiServer);
		final AppleMidiSessionAppleMidiServer midiServer = currentSessions.get(appleMidiEndSession.getSsrc());
		if (midiServer != null) {
			midiServer.getAppleMidiSession().onEndSession(appleMidiEndSession, appleMidiServer);
		}
		final AppleMidiSessionAppleMidiServer sessionTuple = currentSessions.remove(appleMidiEndSession.getSsrc());
		if (sessionTuple != null) {
			sessions.add(sessionTuple.getAppleMidiSession());
			notifyMaxNumberOfSessions();
		}
	}

	@Override
	public void onMidiMessage(final MidiCommandHeader midiCommandHeader, final MidiMessage message, final int timestamp) {
		final AppleMidiSessionAppleMidiServer sessionTuple = currentSessions.get(midiCommandHeader.getRtpHeader().getSsrc());
		if (sessionTuple != null) {
			sessionTuple.getAppleMidiSession().onMidiMessage(midiCommandHeader, message, timestamp);
		} else {
			log.debug("Could not find session for ssrc: {}", ssrc);
		}
	}

	public void sendMidiMessage(List<MidiMessage> messages, AppleMidiServer appleMidiServer) throws Exception {
		MidiCommandHeader header = new MidiCommandHeader(false, false, false, false, (short) 0, new RtpHeader((byte) 2, false, false, (byte) 0, false, (byte) 97, sequenceNumber++, 0, ssrc));
		AppleMidiMessage msg = new AppleMidiMessage(header, messages);
		AppleMidiCommand amc = new AppleMidiCommand(null, ssrc) {

			@Override
			public byte[] toByteArray() throws IOException {
				return msg.getBytes();
			}
		};
		if (appleMidiServer != null) {
			send(amc, appleMidiServer);
		} else {
			currentSessions.values().stream().forEach(o -> executorService.submit(() -> {
				send(amc, o.appleMidiServer);
				return null;
			}));
		}
	}

	/**
	 * Add a new {@link AppleMidiSession} to this server
	 *
	 * @param session The session to be added
	 */
	public void addAppleMidiSession(@Nonnull final AppleMidiSession session) {
		sessions.add(session);
		notifyMaxNumberOfSessions();
	}

	/**
	 * Remove the {@link AppleMidiSession} from this server
	 *
	 * @param session The session to be removed
	 */
	public void removeAppleMidiSession(@Nonnull final AppleMidiSession session) {
		sessions.remove(session);
		for (final Map.Entry<Integer, AppleMidiSessionAppleMidiServer> entry : currentSessions.entrySet()) {
			if (entry.getValue().getAppleMidiSession().equals(session)) {
				final Integer ssrc = entry.getKey();
				currentSessions.remove(ssrc);
				notifyMaxNumberOfSessions();
			}
		}
	}

	/**
	 * Informs all {@link SessionChangeListener} about the new number of available sessions
	 */
	private void notifyMaxNumberOfSessions() {
		for (final SessionChangeListener listener : sessionChangeListeners) {
			listener.onMaxNumberOfSessionsChange(sessions.size());
		}
	}

	/**
	 * @return The current number of available sessions for receiving MIDI messages
	 */
	public int getNumberOfAvailableSessions() {
		return sessions.size();
	}

	/**
	 * Registers a new {@link SessionChangeListener}
	 *
	 * @param listener The listener to be registerd
	 */
	public void registerSessionChangeListener(@Nonnull final SessionChangeListener listener) {
		sessionChangeListeners.add(listener);
	}

	/**
	 * Unregisters a {@link SessionChangeListener}
	 *
	 * @param listener The listener to be unregisterd
	 */
	public void unregisterSessionChangeListener(@Nonnull final SessionChangeListener listener) {
		sessionChangeListeners.remove(listener);
	}

}
