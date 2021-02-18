import java.rmi.*;

/**
 * @author Tommaso Lencioni 560309
 */
public interface ServerUpdaterInterface extends Remote {
    void registerForCallback (String username, ClientUpdaterInterface ClientInterface) throws RemoteException;
    void unregisterForCallback(String username, ClientUpdaterInterface Client) throws RemoteException;
    void update(String nome, boolean status) throws RemoteException;
    void makeJoin(String userName, String projectName, String multicastAddress) throws RemoteException;
    void makeUnJoin(String userName, String projectName) throws RemoteException;
}