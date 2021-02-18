import org.apache.commons.cli.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Tommaso Lencioni 560309
 */
public class MainClient {
    protected static final boolean DEBUG=false;
    //Porte note
    //private static final int RMIPORT = 9999;
    private static final int RMIPORT = 10000;
    private static final int CALLBACKPORT = 10000;
    protected static final int MULTICASTPORT= 10001;
    //Indirizzo e porta del server
    private static String SERVERADDRESS;
    private static int SERVERPORT;
    //Stub del ROS per registrarsi/disiscriversi al servio di callback
    private static ServerUpdaterInterface serverCallback;
    //Stub da registrare per le callback del server
    private static ClientUpdaterInterface stub;
    protected static NetworkInterface ni;
    private static DatagramChannel datagramChannel;
    //Username corrente (puo' cambiare se si effettua un nuovo login sullo stesso client)
    private static String currentUser;
    //Struttura contenente lo status attuale di tutti gli utenti registrati
    protected static HashMap<String, Boolean> allUsers;
    //HashMap delle chat indicizzate con il nome del progetto e rappresentate come una LinkedList di stringhe
    protected static ConcurrentHashMap<String, LinkedList<String>> chats;
    //HashMap di indirizzi multicast indicizzati col nome del progetto
    protected static ConcurrentHashMap<String, String> multicastGroups;
    //Stream in input da stdin
    private static final BufferedReader scanner = new BufferedReader(new InputStreamReader(System.in));

    public static void main(String[] args) {
        //PARSING ARGOMENTI---------------------------------------------------------------------------------------------
        String[] argomenti;
        try{
            argomenti=argParser(args);
        }
        catch (org.apache.commons.cli.ParseException e){
            return;
        }
        SERVERADDRESS=argomenti[0];
        SERVERPORT=Integer.parseInt(argomenti[1]);

        System.out.printf("--- WORTH ---\n");
        try {
            while (true) {
                System.out.printf("Cosa vuoi fare?\n1: Register\t\t2: Login\t\t3: Help\t\t4: Quit\n> ");
                //Normalizzo l'input in lowercase per permettere l'inserimento non case-sensitive
                switch (scanner.readLine().trim().toLowerCase(Locale.ROOT)) {
                    //REGISTRAZIONE-----------------------------------------------------------------------------------------------------------------
                    case "1": case "r": case "register": {
                        registrati();
                        break;
                    }
                    //LOGIN------------------------------------------------------------------------------------------------------------------------
                    case "2": case "l": case "login": {
                        //Flag che indica la volonta' dell'utente di continuare la sessione, viene settato a false in caso di logout
                        boolean exit = false;
                        //Socket alla quale il client si dovra' collegare
                        Socket socket = new Socket();
                        //Wrapper dei canali bufferizzati ottenuti dalla socket
                        BufferedReader reader;
                        BufferedWriter writer;
                        try {
                            socket.connect(new InetSocketAddress(SERVERADDRESS, SERVERPORT));
                            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                            //Setto di default la network interface di loopback
                            InetAddress niAddress= InetAddress.getByName("127.0.0.1");
                            /*
                            //PROVA DI RICERCA INTERFACCE DI RETE CON INDIRIZZO IPv4 PRIVATO, NON FA PARTE DEL CODICE
                            Enumeration interfaces =NetworkInterface.getNetworkInterfaces();
                            while(interfaces.hasMoreElements()) {
                                NetworkInterface ni = (NetworkInterface) interfaces.nextElement();
                                //Itero sugli indirizzi associati all'interfaccia che sto considerando
                                Enumeration interfaceAdd = ni.getInetAddresses();
                                while (interfaceAdd.hasMoreElements()) {
                                    InetAddress ina = (InetAddress) interfaceAdd.nextElement();
                                    //Standardizzo l'utilizzo dell'indirizzo IPv4 privato per l'interfaccia che
                                    // dovra' inviare e ricevere datagrammi UDP multicast
                                    if (ina.getHostAddress().startsWith("192.168")) niAddress = ina;
                                }
                            }
                            */
                            ni = NetworkInterface.getByInetAddress(niAddress);
                            //Apro il DatagramChannel su IPv4, permetto il riuso,
                            // faccio il bind sulla porta multicast e setto l'interfaccia ottenuta sopra
                            datagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                                    .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                                    .bind(new InetSocketAddress(MULTICASTPORT))
                                    .setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                            allUsers = new HashMap<>();
                            chats = new ConcurrentHashMap<>();
                            multicastGroups=new ConcurrentHashMap<>();
                            String currentUser = login(reader, writer);
                            Runtime currentApp=null;
                            ShutdownHook hook=null;
                            if(!currentUser.equals(" errore")){
                                currentApp = Runtime.getRuntime();
                                hook=new ShutdownHook(writer, socket);
                                currentApp.addShutdownHook(hook);
                            }
                            //Se l'utente e' loggato correttamente e finche' l'utente non fa il logout (dove setto anche exit=true)
                            while (!currentUser.equals(" errore") && !exit) {
                                System.out.print("-----------------------------------------------------------------\n" +
                                         currentUser + " cosa vuoi fare?\n" +
                                        "1: List Users\t2: List Online Users\n" +
                                        "3: Create Projects\t4: List Projects\t5: Add Member\t6: Show Members\t7: Cancel Project\n" +
                                        "8: Add Card\t9: Show Cards\t10: Show Card\t11: Move Card\t12: Get Card History\n" +
                                        "13: Send Chat Message\t14: Read Chat\t15: Logout\t16: Help\n> ");
                                try {
                                    //Normalizzo l'input in lowercase per permettere l'inserimento non case-sensitive
                                    switch (scanner.readLine().replaceAll(" ","").toLowerCase(Locale.ROOT)) {
                                        //LISTUSERS-----------------------------------------------------------------
                                        case "1":case "lu": case "listusers": {
                                            listUsers(allUsers);
                                            break;
                                        }
                                        //LISTONLINEUSERS---------------------------------------------------------------
                                        case "2": case "lo": case "listonlineusers":{
                                            listOnlineusers(allUsers);
                                            break;
                                        }
                                        //CREATEPROJECT-----------------------------------------------------------------
                                        case "3": case "cp": case "createproject" :{
                                            createProject(reader, writer);
                                            break;
                                        }
                                        //LISTPROJECTS------------------------------------------------------------------
                                        case "4": case "lp": case "listprojects": {
                                            listProjects(reader, writer);
                                            break;
                                        }
                                        //ADDMEMBER---------------------------------------------------------------------
                                        case "5": case "am": case "addmember":{
                                            addMember(reader, writer);
                                            break;
                                        }
                                        //SHOWMEMBERS-------------------------------------------------------------------
                                        case "6": case "sm": case "showmembers":{
                                            showMembers(reader, writer);
                                            break;
                                        }
                                        //CANCELPROJECT-----------------------------------------------------------------
                                        case "7": case "rmp": case"cancelproject":{
                                            cancelProject(reader, writer);
                                            break;
                                        }//ADDCARD-----------------------------------------------------------------------
                                        case "8": case "ac": case "addcard": {
                                            addCard(reader, writer);
                                            break;
                                        }
                                        //SHOWCARDS---------------------------------------------------------------------
                                        case "9": case "scs": case "showcards": {
                                            showCards(reader, writer);
                                            break;
                                        }
                                        //SHOWCARD----------------------------------------------------------------------
                                        case "10": case "sc": case "showcard":{
                                            showCard(reader, writer);
                                            break;
                                        }
                                        //MOVECARD----------------------------------------------------------------------
                                        case "11": case "mc": case "movecard": {
                                            moveCard(reader, writer);
                                            break;
                                        }
                                        //GETCARDHISTORY----------------------------------------------------------------
                                        case "12": case "ch": case "getcardhistory": {
                                            getCardHistory(reader, writer);
                                            break;
                                        }
                                        //SENDCHATMSG-------------------------------------------------------------------
                                        case "13":case "wc": case "sendchatmsg" : {
                                            sendChatMsg();
                                            break;
                                        }
                                        //READCHAT----------------------------------------------------------------------
                                        case "14":case "rc": case "readchat": {
                                            readChat(chats);
                                            break;
                                        }
                                        //LOGOUT------------------------------------------------------------------------
                                        case "15":case "q":case "e": case "logout":{
                                            writer.write("logout");
                                            writer.newLine();
                                            writer.flush();
                                            serverCallback.unregisterForCallback(currentUser, stub);
                                            //Rimuovo lo shudown hook, una volta effettuato il logout non e' piu' un problema la terminazione del client
                                            currentApp.removeShutdownHook(hook);
                                            exit = true;
                                            break;
                                        }
                                        case "16": case "h": case "help":{
                                            System.out.println("\n" + currentUser + " puoi usare i seguenti comandi (tutti non case-sensitive):\n" +
                                                    "\t1, lu, listusers ->\tMostra tutti gli utenti registrati e il loro stato attuale (Online o Offline)\n" +
                                                    "\t\tQuesto comando non interroga il server.\n" +
                                                    "\t2, lo, listonlineusers ->\tMostra tutti gli utenti registrati attualmente online\n" +
                                                    "\t\tQuesto comando non interroga il server.\n" +
                                                    "\t3, cp, createproject ->\tCrea un nuovo progetto\n" +
                                                    "\t\tEventuali spazi nel nome verranno sostituiti con underscore, non somo ammessi caratteri speciali.\n" +
                                                    "\t4, lp, listprojects ->\tMostra tutti i progetti dei quali"+ currentUser +" e'membro\n" +
                                                    "\t5, am, addmember ->\tAggiunge un membro a un progetto\n" +
                                                    "\t6, sm, showmembers ->\tMostra i membri di un progetto\n" +
                                                    "\t7, rmp, cancelproject ->\tElimina un progetto\n" +
                                                    "\t\tTutte le sue cards devono essere nella lista DONE prima dell'eliminazione.\n" +
                                                    "\t8, ac, addcard ->\tAggiunge una card a un progetto\n" +
                                                    "\t\tNon sono ammessi spazi o caratteri speciali ne' per il nome della card ne' per la sua descrizione.\n" +
                                                    "\t\tL'avvenuto inserimento viene notificato sulla chat del progetto.\n" +
                                                    "\t9, scs, showcards ->\tMostra le carte di un progetto\n" +
                                                    "\t10, sc, showcard ->\tMostra una card di un progetto\n" +
                                                    "\t11, mc, movecard ->\tSposta una carta tra due liste di un progetto\n" +
                                                    "\t\tL'avvenuto spostamento viene notificato sulla chat del progetto.\n" +
                                                    "\t\tI vincoli sulle liste sono i seguenti:\n" +
                                                    "                 +----------------+\n" +
                                                    "                 |                |\n" +
                                                    "                 v                |\n" +
                                                    "+------+   +-----+------+   +-----+-------+   +------+\n" +
                                                    "| TODO +-->+ INPROGRESS +-->+ TOBEREVISED +-->+ DONE |\n" +
                                                    "+------+   +-----+------+   +-------------+   +---+--+\n" +
                                                    "                 |                                ^\n" +
                                                    "                 |                                |\n" +
                                                    "                 +--------------------------------+\n" +
                                                    "\t12, ch, getcardhistory ->\tMostra lo storico degli spostamenti di una carta\n" +
                                                    "\t\tLo storico viene mostrato dalla lista meno recente a quella piu' recente.\n" +
                                                    "\t13, wc, sendchatmsg ->\tInvia un messaggio sulla chat di un progetto\n" +
                                                    "\t\tQuesto comando non interroga il server\n" +
                                                    "\t14, rc, readchat ->\tLeggi la chat di un progetto\n" +
                                                    "\t\tI messaggi vengono mostrati dal meno recente al più recente\n" +
                                                    "\t\tQuesto comando non interroga il server\n" +
                                                    "\t15, q, e, logout ->\tEffettua il logout\n" +
                                                    "\t16, h, help ->\tMostra queste informazioni\n\n" +
                                                    "Gli eventuali dati in input verranno richiesti dopo aver selezionato il comando\n");
                                            break;
                                        }
                                        default: {
                                            System.out.println("< Operazione non riconosciuta");
                                        }
                                    }
                                } catch (InputMismatchException e) {
                                    System.out.println("< Operazione non riconosciuta");
                                } catch (RemoteException e) {
                                    System.out.println("* Errore con l'oggetto remoto");
                                    if(DEBUG) e.printStackTrace();
                                }
                            }
                            //Se ho avuto errori di login o desidero effettuare il logout chiudo la socket
                            socket.close();
                            break;
                        }
                        //Se il server viene chiuso in qualsiasi momento della comunicazione col client l'eccezione galleggia fino a qui
                        catch (SocketException | NullPointerException e) {
                            System.err.println("* Impossibile connettersi al server");
                            if(DEBUG) e.printStackTrace();
                        }
                        break;
                    }
                    case "3":
                    case "h":
                    case "help":{
                        System.out.println("\nWORTH e' uno strumento per la gestione di progetti\n" +
                                "collaborativi che si ispira ad alcuni principi della metodologia Kanban\n\n" +
                                "In questo menu' iniziale e' possibile scegliere i seguenti comandi (tutti non case-sensitive):\n" +
                                "\t1, r, register ->\tPermette all'utente di registrare il proprio\n" +
                                "username e password con i quali effettuare il login.\n" +
                                "\t\t\tNon sono ammessi spazi all'interno dell'username. Verra' considerato parte della password\n" +
                                "\t\t\tqualsiasi carattere successivo al primo spazio dopo lo username.\n" +
                                "\t2, l, login ->\tPermette all'utente di accedere a WORTH inserendo il proprio username e password.\n" +
                                "\t3, h, help ->\tVisualizza queste istruzioni\n" +
                                "\t4, q, quit, e ->\tTermina l'esecuzione del client con codice di uscita 0\n");
                        break;
                    }
                    case "4": case "q": case "quit": case "e": {
                        System.exit(0);
                    }
                    default:
                        System.out.println("< Operazione non riconosciuta");
                }
            }
        }
        catch (IOException e){
            System.err.println("Un errore e' accaduto");
            if(DEBUG) e.printStackTrace();
        }
    }

    //WELCOME-----------------------------------------------------------------------------------------------------------
    private static void registrati() throws IOException{
        //Stub sul quale fare RMI di addUser
        RegisterServiceInterface registrationObject;
        //Parsing input
        System.out.print("Inserisci username e password (non sono accettati spazi nel nome o nella password)\n> ");
        String rawinput = scanner.readLine();
        String name, password;
        try {
            if (rawinput.startsWith(" ")) rawinput = rawinput.replaceFirst("\\s+", "");
            name = rawinput.substring(0, rawinput.indexOf(" "));
            password = rawinput.substring(rawinput.indexOf(" ") + 1);
        } catch (StringIndexOutOfBoundsException e) {
            System.out.println("< Errore di formattazione: username e password separati da uno spazio");
            return;
        }
        if (name.length() == 0 || password.length() == 0) {
            System.out.println("< Errore: Formattazione errata, username e password separati da uno spazio");
            return;
        }
        try {
            Registry registry = LocateRegistry.getRegistry(RMIPORT);
            registrationObject = (RegisterServiceInterface) registry.lookup("REGISTRATION");
            if (registrationObject.addUser(name, password)) {
                System.out.println("< Utente registrato con successo");
            } else {
                System.out.println("< Errore: Nome utente gia' esistente");
            }
        } catch (RemoteException | NotBoundException e) {
            System.err.println("* Problema di ottenimento dell'oggetto remoto, verificare che i parametri di RMI siano corretti e il server sia up");
        }
    }

    private static String login(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci username e password separati da uno spazio\n> ");
        String rawinput = scanner.readLine();
        String reply;
        writer.write("login " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                currentUser = reader.readLine();
                String tmpname;
                String tmpstatus;
                while (!((tmpname = reader.readLine()).equals(" end"))) {
                    tmpstatus = reader.readLine();
                    if (tmpstatus.equals("ON")) allUsers.put(tmpname, true);
                    else allUsers.put(tmpname, false);
                }
                //Registrazione al servizio di callback
                try {
                    Registry registry = LocateRegistry.getRegistry(CALLBACKPORT);
                    serverCallback = (ServerUpdaterInterface) registry.lookup("UPDATE");
                    ClientUpdaterInterface callbackObj = new ClientUpdater();
                    stub = (ClientUpdaterInterface) UnicastRemoteObject.exportObject(callbackObj, 0);
                    serverCallback.registerForCallback(currentUser,stub);
                } catch (NotBoundException | RemoteException e) {
                    System.err.println("* Servizio di update non trovato");
                    return " errore";
                }
                //Dopo essermi registrto al servizio di callback avverto il server
                writer.write("ok");
                writer.newLine();
                writer.flush();
                //Attendo l'avvenuta join delle chat
                if (reader.readLine().equals("done")) System.out.println("< Login effettuato con l'utente "+ currentUser );
                else {
                    System.out.println("* Join delle chat fallito");
                    return " errore";
                }
                return currentUser;
            }
            case "-1": {
                System.out.println("< Errore: Formattazione errata, username e password devono essere separati da uno spazio");
                return " errore";
            }
            case "-2": {
                System.out.println("< Errore: Utente "+ reader.readLine() +" non registrato");
                return " errore";
            }
            case "-3": {
                System.out.println("< Errore: Password errata");
                return " errore";
            }
            case "-4": {
                System.out.println("< Errore: Utente "+ reader.readLine() +" gia' loggato, effettuare prima il logout");
                return " errore";
            }
            default: {
                System.err.println("< Errore sconosciuto");
                return " errore";
            }
        }
    }

    //UTENTI------------------------------------------------------------------------------------------------------------
    private static void listUsers(HashMap<String, Boolean> allUsers) {
        System.out.println("< ---UTENTI REGISTRATI");
        //Ottengo la situazione attuale delle entry in allUsers
        // per permettere la modifica concorrente da parte del ClientUpdater
        HashMap<String, Boolean> tmpUtenti = new HashMap<>(allUsers);
        for (Map.Entry<String, Boolean> user : tmpUtenti.entrySet()) {
            //Per ogni entry stampo l'username e verifico se il suo stato e' online nel momento in cui ho osservato i valori della struttura
            System.out.print(user.getKey() + "\t\t\t");
            if (user.getValue()) System.out.print("ON\n");
            else System.out.print("OFF\n");
        }
    }

    private static void listOnlineusers(HashMap<String, Boolean> allUsers) {
        System.out.println("< ---UTENTI ONLINE");
        //Ottengo la situazione attuale delle entry in allUsers
        // per permettere la modifica concorrente da parte del ClientUpdater
        HashMap<String, Boolean> tmpUtenti = new HashMap<>(allUsers);
        for (Map.Entry<String, Boolean> user : tmpUtenti.entrySet()) {
            //Per ogni entry stampo l'username se il suo stato e' online nel momento in cui ho osservato i valori della struttura
            if (user.getValue()) System.out.println(user.getKey());
        }
    }

    //PROGETTI----------------------------------------------------------------------------------------------------------
    private static void createProject(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto (non puo' contenere spazi)\n> ");
        String rawinput = scanner.readLine();
        //Invio al server il comando e l'input dell'utente
        writer.write("createProject " + rawinput);
        writer.newLine();
        writer.flush();
        String reply = reader.readLine();
        switch (reply) {
            case "0": {
                System.out.println("< Progetto " + reader.readLine() +" creato con successo");
                break;
            }
            case "-1": {
                System.out.println("< Errore: Formattazione errata, il nome non deve contenere spazi");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto " + reader.readLine() +" gia' esistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Serializzazione fallita, operazione annullata");
                break;
            }
            case "-4": {
                System.out.println("< Errore: Numero massimo di progetti raggiunto");
                break;
            }
            case "-5": {
                System.out.println("< Errore: Serializzazione fallita, stato inconsistente del progetto, contattare l'amministratore");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void listProjects(BufferedReader reader, BufferedWriter writer) throws IOException {
        //Invio al server il comando
        writer.write("listProjects");
        writer.newLine();
        writer.flush();
        String res;
        System.out.println("<--- PROGETTI");
        while (!((res = reader.readLine()).equals(" end"))) {
            System.out.println(res);
            System.out.flush();
        }
    }

    private static void addMember(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto e il nome dell'utente da aggiungere\n> ");
        String rawinput = scanner.readLine();
        //Invio al server il comando e l'input dell'utente
        writer.write("addMember " + rawinput);
        writer.newLine();
        writer.flush();
        String reply = reader.readLine();
        switch (reply) {
            case "0": {
                System.out.println("< L'utente e' stato aggiunto correttamente al progetto");
                break;
            }
            case "-1": {
                System.out.println("< Errore: Formattazione errata, inserisci nome del progetto e nome utente separati da uno spazio");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Utente non registrato");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Progetto inesistente");
                break;
            }
            case "-4": {
                System.out.println("< Errore: Non sei membro del progetto");
                break;
            }
            case "-5": {
                System.out.println("< Utente gia' membro del progetto indicato");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void showMembers(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("showMembers " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                String name;
                System.out.println("< ---MEMBRI DEL PROGETTO");
                while (!((name = reader.readLine()).equals(" end"))) {
                    System.out.println(name);
                }
                break;
            }
            case "-1": {
                System.out.println("< Errore: Formattazione errata, nome vuoto");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto "+ reader.readLine() +" inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto " + reader.readLine());
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void cancelProject(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("cancelProject " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                System.out.println("< Cancellazione del progetto avvenuta con successo");
                break;
            }
            case "-1": {
                System.out.println("< Errore di formattazione: Il nome non deve contenere spazi");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto");
                break;
            }
            case "-4": {
                System.out.println("< Errore: Non tutte le cards in DONE");
                break;
            }
            case "-5": {
                System.out.println("< Errore: Serializzazione fallita, operazione annullata");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    //CARDS-------------------------------------------------------------------------------------------------------------
    private static void addCard(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto, il nome della card e la sua descrizione\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("addCard " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                System.out.println("< Card "+ reader.readLine() +" aggiunta con successo al progetto "+ reader.readLine());
                break;
            }
            case "-1": {
                System.out.println("< Errore di formattazione: Inserisci nome del progetto, nome della card e la sua descrizione separati da uno spazio");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto "+ reader.readLine() +" inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto "+ reader.readLine());
                break;
            }
            case "-4": {
                System.out.println("< Errore: Card "+ reader.readLine() +" gia' presente nel progetto "+ reader.readLine());
                break;
            }
            case "-5": {
                System.out.println("< Errore: Serializzazione fallita, operazione annullata");
                break;
            }
            case "-6": {
                System.out.println("< Card "+ reader.readLine() +" aggiunta con successo al progetto "+ reader.readLine() +" ma non e' stato possibile notificarlo in chat");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void showCards(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("showCards " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                String name, status;
                System.out.println("< ---CARDS NEL PROGETTO");
                while (!((name = reader.readLine()).equals(" end"))) {
                    status=reader.readLine();
                    System.out.println("Nome: "+ name +"\t\t\tStatus: "+ status);
                }
                break;
            }
            case "-1": {
                System.out.println("< Errore di formattazione: Inserisci nome del progetto");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void showCard(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto e della card\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("showCard " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                String res;
                System.out.println("< ---INFORMAZIONI CARD");
                System.out.println("Nome: "+ reader.readLine());
                System.out.println("Descrizione: "+ reader.readLine());
                System.out.println("Lista: "+ reader.readLine());
                break;
            }
            case "-1": {
                System.out.println("< Errore di formattazione: Inserisci nome del progetto e della card");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto");
                break;
            }
            case "-4": {
                System.out.println("< Errore: Card non presente nel progetto");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void moveCard(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto, della card, della lista di partenza e di quella di destinazione\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("moveCard " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                System.out.println("< Spostamento effettuato con successo");
                break;
            }
            case "-1": {
                System.out.println("< Errore: Formattazione errata, i 4 campi devono essere separati da uno spazio");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto");
                break;
            }
            case "-4": {
                System.out.println("< Errore: Card non presente nel progetto");
                break;
            }
            case "-5": {
                System.out.println("< Errore: Lista di partenza sconosciuta");
                break;
            }
            case "-6": {
                System.out.println("< Errore: Lista di destinazione sconosciuta");
                break;
            }
            case "-7": {
                System.out.println("< Errore: Card non appartenente alla lista di partenza indicata");
                break;
            }
            case "-8": {
                System.out.println("< Errore: Lista di partenza e di destinazione coincidenti");
                break;
            }
            case "-9": {
                System.out.println("< Errore: Vincolo tra liste non rispettato (vedere diagramma a stati finiti)");
                break;
            }
            case "10": {
                System.out.println("< Spostamento effettuato con successo ma non e' stato possibile comunicarlo sulla chat");
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void getCardHistory(BufferedReader reader, BufferedWriter writer) throws IOException {
        System.out.print("Iserisci il nome del progetto e della card\n> ");
        String rawinput = scanner.readLine();
        String reply;
        //Invio al server il comando e l'input dell'utente
        writer.write("getCardHistory " + rawinput);
        writer.newLine();
        writer.flush();
        reply = reader.readLine();
        switch (reply) {
            case "0": {
                String name;
                System.out.println("< ---HISTORY DELLA CARD");
                while (!((name = reader.readLine()).equals(" end"))) {
                    System.out.println(name);
                }
                break;
            }
            case "-1": {
                System.out.println("< Errore: Formattazione errata, progetto e carta devono esssere separati da uno spazio");
                break;
            }
            case "-2": {
                System.out.println("< Errore: Progetto " + reader.readLine() +" inesistente");
                break;
            }
            case "-3": {
                System.out.println("< Errore: Non sei membro del progetto " + reader.readLine());
                break;
            }
            case "-4": {
                System.out.println("< Errore: Card non presente nel progetto" + reader.readLine());
                break;
            }
            default: {
                System.err.println("< Errore sconosciuto");
                break;
            }
        }
    }

    private static void sendChatMsg() throws IOException {
        System.out.print("Iserisci il nome del progetto e il messaggio\n> ");
        String projectName, message;
        String rawinput = scanner.readLine().trim();
        try {
            //Nome del progetto che passero' poi al server per ottenere il suo indirizzo di multicast
            projectName = rawinput.substring(0,rawinput.indexOf(" "));
            //La stringa dopo il primo spazio successivo al nome del progetto comporra' il messaggio (quindi puo' includere anche spazi)
            message = rawinput.substring(rawinput.indexOf(" ")+1).trim();
        } catch (IndexOutOfBoundsException e) {
            System.out.println("< Errore: Formattazione errata, inserisci il nome del progetto e il messaggio separati da uno spazio");
            return;
        }
        String addr;
        if((addr=multicastGroups.get(projectName))==null) System.out.println("< Errore: Non sei membro del progetto " + projectName);
        else{
            InetAddress group = InetAddress.getByName(addr);
            InetSocketAddress address = new InetSocketAddress(group, MULTICASTPORT);
            datagramChannel.send(ByteBuffer.wrap((projectName +":"+ currentUser +": \""+ message +"\"").getBytes(StandardCharsets.UTF_8)), address);
            System.out.println("< Messaggio inviato");
        }
    }

    private static void readChat(ConcurrentHashMap<String, LinkedList<String>> chats) throws IOException{
        System.out.print("Iserisci il nome del progetto\n> ");
        String projectName = scanner.readLine().trim();
        LinkedList<String> tmpChat= chats.get(projectName);
        //Controllo se ho una entry corrispondente a projectName nelle chat
        if(tmpChat==null) System.out.println("< Progetto "+ projectName +" inesistente o non ne sei membro");
        else {
            synchronized (tmpChat) {
                System.out.println("<---Chat " + projectName);
                while (!tmpChat.isEmpty()) {
                    System.out.println("< " + tmpChat.pollFirst());
                }
            }
        }
    }

    //Thread che viene eseguito prima della terminazione del programma se e' ancora installato
    // (quindi solo in seguito a SIGINT o SIGTERM se l'utente non ha ancora fatto il logout)
    private static class ShutdownHook extends Thread {
        BufferedWriter writer;
        Socket socket;

        //Nel costruttore prendo lo stream in output della socket e la socket stessa da chiudere
        ShutdownHook(BufferedWriter w, Socket s){
            writer=w;
            socket=s;
        }

        @Override
        public void run() {
            try {
                writer.write("logout");
                writer.newLine();
                writer.flush();
                serverCallback.unregisterForCallback(currentUser,stub);
                socket.close();
            }
            catch (IOException e){
                System.err.println("* Impossibile connettersi al server");
                if(DEBUG) e.printStackTrace();
            }
        }
    }

    /**
     * Funzione che effettua il parsing delle opzioni
     * Ho introddotto le seguenti opzioni (entrambe opzionali):
     *  h, host: Indirizzo al quale collegarsi
     *  p, port: Porta sulla quale collegarsi
     * @param args array di stringhe che vegono passate come argomento durante l'esecuzione
     * @return Un array con i valori degli argomenti
     * @throws org.apache.commons.cli.ParseException in caso di errore nel parsing degli argomenti
     */
    private static String[] argParser (String[] args) throws org.apache.commons.cli.ParseException {
        //Contatore delle opzioni
        int n=0;

        Options options = new Options();

        Option hostServer = new Option("h", "host", true, "server address");
        hostServer.setRequired(false);
        options.addOption(hostServer);
        ++n;

        Option portServer = new Option("p", "port", true, "server port number");
        portServer.setRequired(false);
        options.addOption(portServer);
        ++n;

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args, false);
        }
        catch (org.apache.commons.cli.ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("MainClient", options);
            throw e;
        }

        String[] res= new String[n];
        res[0] = ((cmd.getOptionValue("host")==null) ? "127.0.0.1" : cmd.getOptionValue("host"));
        res[1] = ((cmd.getOptionValue("port")==null) ? "10002" : cmd.getOptionValue("port"));
        return res;
    }
}
