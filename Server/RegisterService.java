import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.util.HashMap;

/**
 * @author Tommaso Lencioni 560309
 */
public class RegisterService extends RemoteServer implements RegisterServiceInterface {
    //Path del file JSON per la serializzazione degli utenti
    private static final File PATHJSON=MainServer.PATHJSONUTENTI;

    //ConcurrentHashMap che contiene gli utenti
    private static final HashMap<String,Utente> users=MainServer.users;

    //ConcurrentHashMap da restituire agli utenti che fanno il login contenente nome e status di tutti gli utenti del sistema
    private static final HashMap<String, Boolean> exposedUsers=MainServer.exposedUsers;

    //Oggetti per la serializzazione
    private final ObjectMapper mapper = new ObjectMapper();
    private static final DataBaseUsers usersDB=MainServer.usersDB;

    //ROS che aggiorna lo stato degli utenti tramite callback
    private static final ServerUpdater updater =MainServer.updater;

    RegisterService(){}

    /**
     * Funzione per la registrazione al sistema di un utente
     * @param nome nome dell'utente che si intende registrare
     * @param password password dell'utente che si intende registrare
     * @return true se l'inserimento ha avuto successo, false altrimenti
     */
    public synchronized boolean addUser (String nome, String password) throws RemoteException {
        synchronized (users) {
            if (users.containsKey(nome)) return false;

            //Aggiungo il nuovo utente all'HashMap utenti lato server, all'oggetto database e all'HashMap esposto (lato client)
            Utente nuovo = new Utente(nome, password);
            usersDB.getUtenti().add(nuovo);
            try {
                mapper.writeValue(PATHJSON, usersDB);
            } catch (Exception e) {
                //Se la serializzazione fallisce rimuovo l'utente dall'oggetto userDB
                usersDB.getUtenti().remove(nuovo);
                if(MainServer.DEBUG) e.printStackTrace();
                return false;
            }
            users.put(nome, nuovo);
            updater.update(nome, false);
        }
        synchronized (exposedUsers){
            exposedUsers.put(nome, false);
            return true;
        }
    }
}
