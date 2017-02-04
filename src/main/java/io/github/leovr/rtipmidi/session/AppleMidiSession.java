package io.github.leovr.rtipmidi.session;

import io.github.leovr.rtipmidi.AppleMidiCommandListener;
import io.github.leovr.rtipmidi.AppleMidiMessageListener;
import io.github.leovr.rtipmidi.messages.AppleMidiClockSynchronization;
import io.github.leovr.rtipmidi.messages.AppleMidiEndSession;
import io.github.leovr.rtipmidi.messages.AppleMidiInvitationRequest;
import io.github.leovr.rtipmidi.messages.MidiCommandHeader;
import io.github.leovr.rtipmidi.model.AppleMidiServer;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.sound.midi.MidiMessage;
import java.lang.management.ManagementFactory;

@Getter
@Setter
public abstract class AppleMidiSession implements AppleMidiMessageListener, AppleMidiCommandListener {

    private long offsetEstimate;

    public long getCurrentTimestamp() {
        return ManagementFactory.getRuntimeMXBean().getUptime() * 10;
    }

    @Override
    public final void onMidiMessage(final MidiCommandHeader midiCommandHeader, final MidiMessage message,
                                    final int timestamp) {
        onMidiMessage(message, timestamp + offsetEstimate);
    }

    protected abstract void onMidiMessage(final MidiMessage message, final long timestamp);

    @Override
    public final void onMidiInvitation(@Nonnull final AppleMidiInvitationRequest invitation,
                                       @Nonnull final AppleMidiServer appleMidiServer) {
    }

    @Override
    public final void onClockSynchronization(@Nonnull final AppleMidiClockSynchronization clockSynchronization,
                                             @Nonnull final AppleMidiServer appleMidiServer) {
    }

    @Override
    public final void onEndSession(@Nonnull final AppleMidiEndSession appleMidiEndSession,
                                   @Nonnull final AppleMidiServer appleMidiServer) {
        onEndSession();
    }

    protected void onEndSession() {
    }
}
