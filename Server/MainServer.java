import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;

/**
 * @author Tommaso Lencioni 560309
 */
public class MainServer {
    //Costanti
    protected static boolean DEBUG=true;
    private static final int RMIPORT=9999;
    private static final int CALLBACKPORT=10000;
    protected static final int MULTICASTPORT=10001;
    //Variabili condivise col LoginService e inizializzate dopo il parsing
    protected static int SERVERPORT;
    protected static String SERVERADDRESS;
    //HashMap contenente i progetti
    protected static HashMap<String,Progetto> progetti = new HashMap<>();
    //Paths per la serializzazione/deserializzazione
    protected final static File PATHJSONUTENTI = new File("./data/users.json");
    protected final static File PATHJSONPROJECTS = new File("./data/projects.json");
    protected final static File PATHDIRPROJECTS = new File("./data/projects");
    //ConcurrentHashMap contenente gli utenti
    protected static HashMap<String,Utente> users = new HashMap<>();
    //ConcurrentHashMap da esporre agli utenti che fanno il login contenente nome e status di tutti gli utenti
    protected static HashMap<String, Boolean> exposedUsers = new HashMap<>();
    //Array di interi condiviso dagli handler che rappresentano dli ultimi 3 bytes dell'indirizzo di
    // multicast che verra' assegnato in modo incrementale alle chat dei nuovi progetti
    protected static Integer[] addressBytes= new Integer[3];
    //Oggetto esposto per fare callback di aggiornamento agli utenti
    protected static ServerUpdater updater;
    //Oggetti stub per la serializzazione che simulano un database
    protected static DataBaseProjects projectsDB= new DataBaseProjects();
    protected static DataBaseUsers usersDB= new DataBaseUsers();

    public static void main (String[] args) {
        //PARSING ARGOMENTI---------------------------------------------------------------------------------------------
        String[] argomenti;
        try{
            //Chiamo la funzione di parsing e salvo gli argomenti in un array
            argomenti=argParser(args);
        }
        catch (org.apache.commons.cli.ParseException e){
            return;
        }
        SERVERADDRESS=argomenti[0];
        SERVERPORT=Integer.parseInt(argomenti[1]);
        DEBUG= Boolean.parseBoolean(argomenti[2]);
        try{
            //Controllo eventuali conflitti con le porte (la porta di multicast, utilizzando UDP, non crea conflitti)
            if(SERVERPORT==RMIPORT || SERVERPORT==CALLBACKPORT) throw new IllegalArgumentException();
            //Testo se posso fare il bind sull'indirizzo specificato alla porta specificata
            ServerSocket s= new ServerSocket();
            s.bind(new InetSocketAddress(SERVERADDRESS, SERVERPORT));
            s.close();
        }
        catch (IOException e){
            System.err.println("Non e' possibile creare il server all'indirizzo scelto");
            return;
        }
        catch (IllegalArgumentException e){
            System.err.println("Porta in conflitto con altri servizi del Server");
            return;
        }

        if(DEBUG){
            System.out.println("Server address: "+ argomenti[0]);
            System.out.println("Socket port: "+ argomenti[1]);
        }

        //DESERIALIZZAZIONE---------------------------------------------------------------------------------------------
        ObjectMapper mapper = new ObjectMapper();
        //  UTENTI
        if(PATHJSONUTENTI.exists()) loadUsers(mapper);
        //  PROGETTI
        loadProjects(mapper);
        //FINE DESERIALIZZAZIONE----------------------------------------------------------------------------------------

        //SERVIZIO DI CALLBACK PER L'UPDATE DEGLI UTENTI (ONLINE/OFFLINE e SERVIZIO DI JOIN/UNJOIN CHAT)----------------
        try{
            updater = new ServerUpdater();
            //Esporto l'updater e ottengo lo stub
            //  Inserendo 0 come porta lascio scegliere al programma una porta anonima
            ServerUpdaterInterface stub=(ServerUpdaterInterface) UnicastRemoteObject.exportObject(updater,0);
            LocateRegistry.createRegistry(CALLBACKPORT);
            //Ottengo il registro data la porta sulla quale l'ho creato e faccio il bind col nome "UPDATE"
            Registry registry=LocateRegistry.getRegistry(CALLBACKPORT);
            registry.bind("UPDATE", stub);
        }
        catch (AlreadyBoundException e){
            System.err.println("Servizio UPDATE gia' presente");
            if(DEBUG) e.printStackTrace();
            System.exit(-1);
        }
        catch (RemoteException e) {
            System.err.println("C'e' stato un problema con l'oggetto remoto");
            if(DEBUG) e.printStackTrace();
            System.exit(-1);
        }

        //RMI PER LA REGISTRAZIONE--------------------------------------------------------------------------------------
        try {
            RegisterService registerService = new RegisterService();
            //Esporto l'oggetto per la registrazione e ottengo lo stub
            //  Inserendo 0 come porta lascio scegliere al programma una porta anonima
            RegisterServiceInterface stub = (RegisterServiceInterface) UnicastRemoteObject.exportObject(registerService, 0);
            LocateRegistry.createRegistry(RMIPORT);
            //Ottengo il registro data la porta sulla quale l'ho creato e faccio il bind col nome "REGISTRATION"
            //Registry registry =LocateRegistry.getRegistry(RMIPORT);
			Registry registry =LocateRegistry.getRegistry(CALLBACKPORT);
            registry.bind("REGISTRATION", stub);
        }
        catch (AlreadyBoundException e){
            System.err.println("Servizio REGISTRATION gia' presente");
            if(DEBUG) e.printStackTrace();
            System.exit(-1);
        }
        catch (RemoteException e){
            System.err.println("C'e' stato un problema con l'oggetto remoto");
            if(DEBUG) e.printStackTrace();
            System.exit(-1);
        }


        //SERVIZIO PER IL LOGIN-----------------------------------------------------------------------------------------
        //Avvio il thread per la gestione delle connessioni
        LoginService  loginService = new LoginService();
        loginService.start();

        System.out.println("--- SERVER READY ---");
    }

    /**
     * Metodo che effettua il parsing delle opzioni
     * Ho introddotto le seguenti opzioni (entrambe opzionali):
     *  -h, --host: Indirizzo sul quale creare il server
     *  -p, --port: Porta sulla quale fare il bind della socket
     *  -d, --debug: Per eseguire il server in modalito' debug
     * @param args array di stringhe che vegono passate come argomento durante l'esecuzione
     * @return Un array con i valori degli argomenti
     * @throws org.apache.commons.cli.ParseException se il parsing fallisce
     */
    private static String[] argParser (String[] args) throws org.apache.commons.cli.ParseException {
        //Contatore delle opzioni
        int n=0;

        Options options = new Options();

        Option hostServer = new Option("h", "host", true, "Server address");
        hostServer.setRequired(false);
        options.addOption(hostServer);
        ++n;

        Option portServer = new Option("p", "port", true, "Server port number");
        portServer.setRequired(false);
        options.addOption(portServer);
        ++n;

        Option debug = new Option("d", "debug", false, "Modalita' debug");
        portServer.setRequired(false);
        options.addOption(debug);
        ++n;

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args, false);
        }
        catch (org.apache.commons.cli.ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("MainServer", options);
            throw e;
        }

        String[] res= new String[n];
        //Di default utilizzo l'indirizzo di loopback e la porta 10002
        res[0] = ((cmd.getOptionValue("host")==null) ? "127.0.0.1" : cmd.getOptionValue("host"));
        res[1] = ((cmd.getOptionValue("port")==null) ? "10002" : cmd.getOptionValue("port"));
        res[2] = ((cmd.hasOption("d")) ? "true" : "false");
        return res;
    }

    /**
     * Metodo che deserializza gli utenti
     * @param mapper oggetto per leggere il file JSON degli utenti
     */
    private static void loadUsers(ObjectMapper mapper){
        try {
            //Leggo l'array "utenti", unica struttura dati dell'oggetto DataBaseUsers utilizzato per la serializzazione
            JsonNode arrNode = mapper.readTree(PATHJSONUTENTI).get("utenti");
            if (arrNode !=null && arrNode.isArray()) {
                //Aggiungo ogni utente gia' registrato all'HashMap e ricostituisco l'oggetto DataBaseUsers
                for (JsonNode objNode : arrNode) {
                    Utente tmpUtente=mapper.treeToValue(objNode, Utente.class);
                    users.put(tmpUtente.getNome(), tmpUtente);
                    usersDB.addUser(tmpUtente);
                    //Aggiungo contestualmente ogni utente nella ConcurrentHashMap esposta con gli status degli utentu (tutti inzialmente offline all'avvio del server9
                    exposedUsers.put(tmpUtente.getNome(), false);
                }
                if(DEBUG) System.out.println("Utenti deserializzati: "+ usersDB.getUtenti().size());
            }
        }
        catch (IOException e){
            //Faccio exit perche' se il file e' presente ma non puo' essere recuperato non ha senso avviare il server
            if(DEBUG) e.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * Metodo che deserializza i progetti
     * @param mapper oggetto per leggere il file JSON dei progetti
     */
    private static void loadProjects(ObjectMapper mapper){
        //Se non esiste creo la directory contenente i dati serializzati
        if(!PATHDIRPROJECTS.exists() && !PATHDIRPROJECTS.mkdirs()){
            System.err.println("Impossibile creare la directory dei progetti");
            System.exit(-1);
        }
        //Inizializzo i bytes degli indirizzi multicast (riservo 225.0.0.0 per i pacchetti di wakeup)
        addressBytes[0]=0;
        addressBytes[1]=0;
        addressBytes[2]=0;
        //Dichiaro il massimo indirizzo e il totale temporaneo (espressi in notazione decimale)
        int max=0, tmpTot;
        if(PATHJSONPROJECTS.exists()){
            try {
                //Leggo l'array "progetti", unica struttura dati dell'oggetto DataBaseProjects utilizzato per la serializzazione
                JsonNode arrNode=mapper.readTree(PATHJSONPROJECTS).get("progetti");
                if (arrNode!=null && arrNode.isArray()) {
                    //Aggiungo ogni progetto gia' presente ai progetti e ricostituisco l'oggetto DataBaseProjects
                    for (JsonNode objNode : arrNode) {
                        Progetto tmpProj = mapper.treeToValue(objNode, Progetto.class);
                        progetti.put(tmpProj.getNome(), tmpProj);
                        projectsDB.getProgetti().add(tmpProj);
                        //Cerco il massimo indirizzo di multicast assegnato
                        String[] tmpAddress = tmpProj.getChatAdd().split("\\.");
                        addressBytes[0]=Integer.parseInt(tmpAddress[1]);
                        addressBytes[1]=Integer.parseInt(tmpAddress[2]);
                        addressBytes[2]=Integer.parseInt(tmpAddress[3]);
                        //Converto l'indirizzo attuale in decimale e controllo se e' maggiore del massimo
                        tmpTot=addressBytes[0]*65536+addressBytes[1]*256+addressBytes[2];
                        if(tmpTot>max) max=tmpTot;
                    }
                    if (DEBUG) System.out.println("Progetti deserializzati: " + projectsDB.getProgetti().size());
                }
            }
            catch (IOException e){
                //Faccio exit perche' se il file e' presente ma non puo' essere recuperato non ha senso avviare il server
                if(DEBUG) e.printStackTrace();
                System.exit(-1);
            }
            //Riconverto il massimo in notazione puntata standard degli indirizzi IPv4
            addressBytes[0]=max/65536;
            max-=addressBytes[0]*65536;
            addressBytes[1]=max/256;
            max-=addressBytes[1]*256;
            addressBytes[2]=max;
        }
    }
}
