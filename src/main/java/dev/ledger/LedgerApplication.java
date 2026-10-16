package dev.ledger;

import dev.ledger.service.LedgerService;
import dev.ledger.server.LedgerHttpServer;
import dev.ledger.storage.memory.InMemoryStorage;
import java.net.InetSocketAddress;

public final class LedgerApplication {
    private static final System.Logger LOG = System.getLogger(LedgerApplication.class.getName());

    private LedgerApplication() {}

    static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        LedgerHttpServer server = new LedgerHttpServer(new LedgerService(new InMemoryStorage()), new InetSocketAddress(host, port));
        // JVM shutdown closes HTTP and its validator resources; it deliberately does not persist ledger state.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        LOG.log(System.Logger.Level.INFO, "Ledger listening at http://" + host + ":" + server.port());
    }
}
