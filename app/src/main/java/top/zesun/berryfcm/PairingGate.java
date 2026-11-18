package top.zesun.berryfcm;

/** One pairing session. A code is accepted only for the discovered, current port. */
final class PairingGate {
    private int generation, port;
    private boolean active, pairing;
    synchronized int begin() { active = true; pairing = false; port = 0; return ++generation; }
    synchronized void cancel() { active = false; pairing = false; port = 0; ++generation; }
    synchronized int generation() { return generation; }
    synchronized boolean searching(int g) { return active && !pairing && g == generation; }
    synchronized boolean found(int g, int p) {
        if (!searching(g) || p <= 0 || p > 65535) return false;
        port = p; return true;
    }
    synchronized boolean ready(int g, int p) { return searching(g) && p > 0 && p == port; }
    synchronized boolean hasPort() { return port > 0; }
    synchronized void lost(int g, int p) { if (ready(g,p)) port = 0; }
    synchronized boolean submit(int g, int p) {
        if (!ready(g,p)) return false;
        pairing = true; return true;
    }
    synchronized boolean isPairing() { return active && pairing; }
    synchronized boolean pairing(int g) { return isPairing() && g == generation; }
}
