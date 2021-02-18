import java.rmi.*;
import java.rmi.server.*;
import java.util.*;

/**
 * @author Tommaso Lencioni 560309
 */
public class ServerUpdater extends RemoteServer implements ServerUpdaterInterface {

    //HashMap dei clients registrati al servizio di callback indicizzati per username
    private HashMap <String, ClientUpdaterInterface> clients;

    public ServerUpdater() throws RemoteException {
        super();
        clients = new HashMap<>();
    }

    /**
     * Metodo per la registrazione di un utente al servizio di callback.
     * @param clientInterface interfaccia dell'oggetto cliente che si registra al servizio
     * @throws RemoteException in caso di problemi con l'oggetto remoto
     */
    public synchronized void registerForCallback (String username, ClientUpdaterInterface clientInterface) throws RemoteException {
        if (!clients.containsKey(username)) {
            clients.put(username, clientInterface);
        }
    }

    /**
     * Metodo per annullare la registrazione alle callback in caso di logout.
     * Contestualmente lascia tutte le chat (interrompe il thread Listener)
     * @param Client Cliente da disiscrivere
     * @throws RemoteException in caso di problemi con l'oggetto remoto
     */
    public synchronized void unregisterForCallback(String username, ClientUpdaterInterface Client) throws RemoteException {
        if(clients.get(username)!=null) clients.get(username).unjoinAll();
        if(clients.remove(username)==null) System.err.println("* Impossibile disiscrivere il cliente");
    }

    /**
     * Metodo esposto per effettuare le callback di aggiornamento status (Online/Offline) di un utente a tutti i clienti registrati
     * @param nome Username dell'utente del quale è cambiato lo status
     * @param status Nuovo status (Online/Offline)
     */
    public void update(String nome, boolean status) throws RemoteException {
        doCallbacks(nome, status);
    }

    /**
     * Metodo esposto per effettuare le chiamate di aggiornamento status ad ogni utente registrato al servizio di callback
     * @param nome Username dell'utente del quale è cambiato lo status
     * @param status Nuovo status (Online/Offline)
     */
    private synchronized void doCallbacks(String nome, boolean status) throws RemoteException {
        //Per ogni entry non nulla notifico lo status dell'utente
        for (ClientUpdaterInterface client : clients.values()) {
            try {
                if (client != null) client.notifyStatus(nome, status);
            } catch (ConnectException e) {
                System.err.println("Un client è terminato forzatamente senza disiscriversi al servizio di callback, è consigliabile riavviare il sever");
            }
        }
    }

    /**
     * Metodo per permettere a un utente di joinare la chat di un progetto
     * @param userName username dell'utente che deve joinare la chat
     * @param projectName nome del progetto del quale joinare la chat
     * @param multicastAddress indirizzo di multicast sul quale verranno inviati i messaggi della chat di progetto
     * @throws RemoteException in casoo di errore nell'utilizzo dell'oggetto remoto
     */
    public synchronized void makeJoin(String userName, String projectName, String multicastAddress) throws RemoteException{
        //Se il membro del gruppo è attualmente iscritto alle callback chiamo joinChat
        if(clients.get(userName)!=null) clients.get(userName).joinChat(projectName, multicastAddress);
    }

    /**
     * Metodo per far lasciare a un utente la chat di un progetto
     * @param userName username dell'utente che deve abbandonare la chat
     * @param projectName nome del progetto del quale abbandonare la chat
     * @throws RemoteException in casoo di errore nell'utilizzo dell'oggetto remoto
     */
    public synchronized void makeUnJoin(String userName, String projectName) throws RemoteException{
        //Se il membro del gruppo è attualmente iscritto alle callback chiamo unjoinChat
        if(clients.get(userName)!=null) clients.get(userName).unjoinChat(projectName);
    }
}