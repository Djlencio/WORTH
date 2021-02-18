import java.rmi.*;

/**
 * @author Tommaso Lencioni 560309
 */
public interface ClientUpdaterInterface extends Remote {
    void notifyStatus(String nome, boolean status) throws RemoteException;

    void joinChat(String projectName, String multicastAddress) throws RemoteException;

    void unjoinChat(String projectName) throws RemoteException;

    void unjoinAll() throws RemoteException;
}
