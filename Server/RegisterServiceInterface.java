import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * @author Tommaso Lencioni 560309
 */
public interface RegisterServiceInterface extends Remote {
    boolean addUser (String n, String p) throws RemoteException;
}
