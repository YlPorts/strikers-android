# Android LAN input relay prototype

This branch contains an experimental one-on-one LAN input relay. It is built as
a separate `lanPrototype` Android variant (`com.ylports.strikers.lan`) so it can
be installed beside the regular app without replacing it.

## How to try it

1. Install the LAN variant on both phones, select the same ROM in each install,
   and connect them to the same Wi-Fi.
2. On the host, choose **Crear sala LAN** and share the displayed IPv4 address
   and port `43821`.
3. On the second phone, choose **Unirse por IP** and enter the host address.
4. Use the same ROM, mode, and teams on both phones, then start the match at
   roughly the same time.

The host remains player 1 and the joining phone remains player 2. The app sends
the current controller state over UDP and injects the other phone's state into
the corresponding game controller slot. The on-screen status reports waiting,
connected, and reconnecting states.

## Scope and limits

This relays controller inputs only. It does not synchronize menus, match start,
random state, simulation timing, or game state, and it has no rollback or state
repair. The two game simulations can diverge even when their inputs match. Only
one guest is accepted. Packets are not authenticated or encrypted, so use a
trusted local network. A real two-phone match test is still needed.

The host retries after a disconnect; it releases its guest slot after five
seconds without a peer. The client retries its hello while the host is
unavailable. Player ownership stays fixed during disconnects.

## Local checks

Protocol codec test:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pedantic \
  android/tests/lan_protocol_test.cpp -o /tmp/strikers-lan-protocol-test
/tmp/strikers-lan-protocol-test
```

Build the co-installable APK with:

```sh
gradle --no-daemon -p android :app:assembleLanPrototype
```
