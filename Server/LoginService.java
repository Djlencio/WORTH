import java.io.IOException;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Tommaso Lencioni 560309
 */
public class LoginService extends Thread {
    private final boolean DEBUG=MainServer.DEBUG;

    LoginService(){
    }

    @Override
    public void run() {
        ExecutorService es;
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(MainServer.SERVERADDRESS, MainServer.SERVERPORT));
            es = Executors.newCachedThreadPool();
            while (true) {
                try {
                    Socket client = server.accept();
                    ClientHandler handler = new ClientHandler(client);
                    es.execute(handler);
                }
                catch (IOException e) {
                    System.out.println("Si e' verificato un errore");
                }
            }
        }
        catch (BindException e){
            System.err.println("Socket gia' in uso");
            if(DEBUG) e.printStackTrace();
        }
        catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
    }
}
