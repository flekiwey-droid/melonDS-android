#ifndef LOCALMULTIPLAYER_H
#define LOCALMULTIPLAYER_H

namespace LocalMultiplayer
{
    // Called by the emulation thread every frame. While the emulator is running, it is the one pumping the MP
    // interface (MPInterface::Process()), so the lobby must not pump it concurrently (ENet is not thread-safe).
    void notifyEmulatorFrame();
}

#endif //LOCALMULTIPLAYER_H
