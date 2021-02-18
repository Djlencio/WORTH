import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author Tommaso Lencioni 560309
 */
public class ClientHandler implements Runnable {
    private final boolean DEBUG=MainServer.DEBUG;

    //Socket sulla quale il client e' connesso
    private final Socket client;
    //Username dell'utente con il quale l'handler mantiene la connessione TCP
    private String currentUser;
    //HashMap condivisa contenente tutti gli utenti indicizzati per username
    private final static HashMap<String, Utente> users=MainServer.users;
    //Struttura contenente tutti gli utenti esposta a chi si connette
    private final static HashMap<String, Boolean> exposedUsers=MainServer.exposedUsers;

    //ROS per RMI Callback
    private final ServerUpdater updater = MainServer.updater;

    //HashMap condivisa di progetti
    private final static HashMap<String, Progetto> progetti=MainServer.progetti;
    //Path file JSON dei progetti
    private final static File PATHJSONPROJECTS=MainServer.PATHJSONPROJECTS;
    //Path directories dei progetti
    private final static File PATHDIRPROJECTS =MainServer.PATHDIRPROJECTS;

    //ObjectMapper per la serializzazione
    private final ObjectMapper mapper = new ObjectMapper();
    //Oggetto condiviso che simula un database
    private final static DataBaseProjects projectsDB=MainServer.projectsDB;

    //Array di interi condiviso dagli handler che rappresentano dali ultimi 3 bytes dell'indirizzo di
    // multicast che verra' assegnato in modo incrementale alle chat dei nuovi progetti
    private final static Integer[] addressBytes=MainServer.addressBytes;

    //Nel costruttore prendo la socket sulla quale si e' connesso il client
    public ClientHandler(Socket c) {
        client = c;
    }

    @Override
    public void run() {
        //Con una try with resources istanzio i buffer per la lettura e scrittura bufferizzata sullo stream TCP di caratteri
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()))) {
            String rawinput;
            //Leggo non appena ho dati disponibili dall'InputStream fino a che la connessione non viene chiusa
            while ((rawinput = reader.readLine()) != null) {
                //Ogni comunicazione da parte del client e formattata come "comando [rawinput]" quindi possiamo ipotizzare che il
                //  try sulla tokenizzazione per ottenere il comando sia initile
                //Se non venissero rispettati i protocolli della comunicazione si rischierebbe lo stallo dell'handler
                String[] inputTokenized = rawinput.split("\\s+");
                String comando;
                comando = inputTokenized[0];
                switch (comando) {
                    //LOGIN---------------------------------------------------------------------------------------------
                    case "login": {
                        login(rawinput.substring(rawinput.indexOf(" ") + 1), reader, writer);
                        break;
                    }
                    //LOGOUT--------------------------------------------------------------------------------------------
                    case "logout": {
                        if (currentUser != null) {
                            synchronized (exposedUsers) {
                                exposedUsers.put(currentUser, false);
                            }
                            updater.update(currentUser, false);
                        }
                        break;
                    }
                    //CREATEPROJECT-------------------------------------------------------------------------------------
                    case "createProject": {
                        createProject((rawinput.substring(rawinput.indexOf(" ") + 1)), writer);
                        break;
                    }
                    //LISTPROJECTS--------------------------------------------------------------------------------------
                    case "listProjects": {
                        listProjects(writer);
                        break;
                    }
                    //ADDMEMBER-----------------------------------------------------------------------------------------
                    case "addMember": {
                        writer.write(addMember(rawinput.substring(rawinput.indexOf(" ") + 1)));
                        writer.newLine();
                        writer.flush();
                        break;
                    }
                    //SHOWMEMBERS---------------------------------------------------------------------------------------
                    case "showMembers": {
                        showMembers(rawinput.substring(rawinput.indexOf(" ") + 1), writer);
                        break;
                    }
                    //ADDCARD-------------------------------------------------------------------------------------------
                    case "addCard": {
                        addCard(rawinput.substring(rawinput.indexOf(" ") + 1),writer);
                        break;
                    }
                    //SHOWCARDS-----------------------------------------------------------------------------------------
                    case "showCards": {
                        showCards(rawinput.substring(rawinput.indexOf(" ") + 1), writer);
                        break;
                    }
                    //SHOWCARD------------------------------------------------------------------------------------------
                    case "showCard": {
                        showCard(rawinput.substring(rawinput.indexOf(" ") + 1), writer);
                        break;
                    }
                    //MOVECARD------------------------------------------------------------------------------------------
                    case "moveCard": {
                        writer.write(moveCard(rawinput.substring(rawinput.indexOf(" ") + 1)));
                        writer.newLine();
                        writer.flush();
                        break;
                    }
                    //GETHISTORY---------------------------------------------------------------------------------------
                    case "getCardHistory": {
                        getCardHistory(rawinput.substring(rawinput.indexOf(" ") + 1), writer);
                        break;
                    }
                    //CANCELPROJECT------------------------------------------------------------------------------------------
                    case "cancelProject": {
                        writer.write(cancelProject(rawinput.substring(rawinput.indexOf(" ") + 1)));
                        writer.newLine();
                        writer.flush();
                        break;
                    }
                    default: {
                        writer.write("Comando non riconosciuto");
                        writer.newLine();
                        writer.flush();
                    }
                }
            }
            //Se esco il ciclo di lettura dallo strema della socket e avevo uno user connesso significa che ha effettuato correttamente il logout
            if (DEBUG && currentUser != null) System.out.println(Thread.currentThread().getName() + ": " + currentUser + " ha effettuato il logout");
        } catch (SocketException e) {
            //Situazione di terminazione forzata, lo stato dell'utente sara' inconsistente fino al prossimo riavvio del server
            if (DEBUG && currentUser != null) System.err.println(Thread.currentThread().getName() + ": " + currentUser + " ha interrotto forzatamente la connessione, e' consigliabile riavviare il server");
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
        if (DEBUG) System.out.println(Thread.currentThread().getName() + ": termino");
    }

    //WELCOME-----------------------------------------------------------------------------------------------------------
    /**
     * Metodo per effetuare il login di un utente
     * @param rawinput Input da parsare e ottenere username e password
     * @param reader Stream di caratteri bufferizzato dal quale leggere le risposte del client
     * @param writer Stream di caratteri bufferizzato sul inviare le informazioni al client
     */
    private void login(String rawinput, BufferedReader reader, BufferedWriter writer) {
        //Codici di stato:
        //  Login effettuato con successo: 0
        //  Errore di formattazione: -1
        //  Username non registrato: -2
        //  Password errata: -3
        //  Utente gia' loggato: -4

        String username, password;
        try {
            //Tokenizzo il rawinput e ottengo username e password
            try {
                //Rimuovo gli spazi prima dell'username
                if(rawinput.startsWith(" ")) rawinput=rawinput.replaceFirst("\\s+", "");
                username=rawinput.substring(0,rawinput.indexOf(" "));
                password=rawinput.substring(rawinput.indexOf(" ")+1);
            } catch (StringIndexOutOfBoundsException e) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            if (username.length() == 0 || password.length() == 0) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            //controllo se l'username e' registrato
            synchronized (users) {
                if (!users.containsKey(username)) {
                    writer.write("-2");
                    writer.newLine();
                    //Invio al client anche l'username con il quale ha provato ad accedere
                    writer.write(username);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                if (!users.get(username).getPassword().equals(password)) {
                    writer.write("-3");
                    writer.newLine();
                    writer.flush();
                    return;
                }
            }
            currentUser = username;
            synchronized (exposedUsers) {
                //Se l'utente e' gia' loggato su un altro client la get su exposedUsers restituira' true
                if (exposedUsers.get(username)) {
                    writer.write("-4");
                    writer.newLine();
                    writer.write(username);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                writer.write("0");
                writer.newLine();
                writer.write(currentUser);
                writer.newLine();
                exposedUsers.put(currentUser, true);
                //Comunico al client la situazione attuale degli utenti registrati
                for (Map.Entry<String, Boolean> entry : exposedUsers.entrySet()) {
                    writer.write(entry.getKey());
                    writer.newLine();
                    //Ottendo lo status corrente dell'utente entry
                    boolean value = entry.getValue();
                    if (value) writer.write("ON");
                    else writer.write("OFF");
                    writer.newLine();
                }
            }
            //Callback per aggiornamento status
            updater.update(currentUser, true);
            //Stringa standardizzata di terminazione elenco
            //  Non potendo contenere spazi nessun utente si potra' chiamare " end"
            writer.write(" end");
            writer.newLine();
            writer.flush();

            //Attendo che il client si sia registrato al servizio di callback prima di farlo joinare alle chat di
            //  tutti i progetti di cui e' membro
            String res = reader.readLine();
            if (res.equals("ok")) {
                synchronized (progetti) {
                    for (Progetto p : progetti.values()) {
                        if (p.getMembers().contains(currentUser))
                            updater.makeJoin(currentUser, p.getNome(), p.getChatAdd());
                    }
                }
            } else {
                if(DEBUG) System.err.println("Errore nella comunicazione col client");
            }
            //Comunico al client l'avvenuta join delle chat
            writer.write("done");
            writer.newLine();
            writer.flush();
            if (DEBUG) System.out.println(Thread.currentThread().getName() + ": " + currentUser + " ha effettuato il login");
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
    }

    //PROGETTI----------------------------------------------------------------------------------------------------------
    /**
     * Metodo per creare un progetto
     * @param projectName Nome del progetto da creare
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void createProject(String projectName, BufferedWriter writer) {
        //Codici di stato:
        //  Progetto inserito con successo: 0
        //  Errore di formattazione: -1
        //  Progetto gia' esistente: -2
        //  Serializzazione fallita: -3
        //  Numero massimo di progetti raggiunto: -4
        //  Serializzazione fallita, stato inconsistente del progetto: -5
        try {
            //Trimmo e rimuovo qualsiasi carattere non alfanumerico dall'input
            projectName = projectName.replaceAll(" ", "_").replaceAll("[^\\w.\\-_]", "");
            if (projectName.length() == 0) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            //Creo un nuovo oggetto Progetto e vi aggiungo currentUser come membro
            Progetto tmpProj = new Progetto(projectName);
            tmpProj.getMembers().add(currentUser);
            File projDir = new File(PATHDIRPROJECTS + "/" + projectName);
            synchronized (progetti) {
                if (progetti.containsKey(projectName)) {
                    writer.write("-2");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                //Comincio a costruire l'indirizzo di multicast da assegnare al progetto
                StringBuilder multicastAddress = new StringBuilder("225.");
                synchronized (addressBytes) {
                    if (addressBytes[2] == 255) {
                        if (addressBytes[1] == 255) {
                            if (addressBytes[0] == 255) {
                                //L'ultimo indirizzo 225.255.255.255 e' gia' stato assegnato
                                writer.write("-4");
                                writer.newLine();
                                writer.flush();
                                return;
                            } else {
                                ++addressBytes[0];
                                addressBytes[1] = 0;
                                addressBytes[2] = 0;
                            }
                        } else {
                            ++addressBytes[1];
                            addressBytes[2] = 0;
                        }
                    } else ++addressBytes[2];
                }
                //Completo l'indirizzo e lo assegno al progetto
                multicastAddress.append(addressBytes[0] + "." + addressBytes[1] + "." + addressBytes[2]);
                tmpProj.setChatAdd(multicastAddress.toString());
                progetti.put(projectName, tmpProj);
                //Se non riesco a creare la cartella rimuovo il progetto appena inserito e restituisco un errore
                // Invertire il contatore degli indirizzi sarebbe inutilmente costoso
                if (!projDir.mkdir()) {
                    progetti.remove(projectName);
                    writer.write("-3");
                    writer.newLine();
                    writer.flush();
                    return;
                }
                projectsDB.getProgetti().add(tmpProj);
                try {
                    mapper.writeValue(PATHJSONPROJECTS, projectsDB);
                    updater.makeJoin(currentUser, projectName, tmpProj.getChatAdd());
                } catch (IOException e) {
                    //Se ho un errore nella serializzazione ripristino gli oggetti appena modificati rimuovendo il progetto
                    progetti.remove(projectName);
                    projectsDB.getProgetti().remove(tmpProj);
                    if(DEBUG) e.printStackTrace();
                    if (!projDir.delete()) {
                        //Se fallisco a l'eliminazione della cartella ho uno stato inconsisente del progetto
                        writer.write("-5");
                        writer.newLine();
                        writer.flush();
                        return;
                    }
                    writer.write("-3");
                    writer.newLine();
                    writer.flush();
                    return;
                }
            }
            writer.write("0");
            writer.newLine();
            writer.write(projectName);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            if (DEBUG) e.printStackTrace();
        }
    }

    /**
     * Metodo per mostrare tutti i progetti dei quali currentUser e' membro
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void listProjects(BufferedWriter writer){
        synchronized (progetti) {
            try {
                for (Progetto entry : progetti.values()) {
                    if (entry.getMembers() != null && entry.getMembers().contains(currentUser)) {
                        writer.write(entry.getNome());
                        writer.newLine();
                    }
                }
                //Stringa standardizzata di terminazione elenco
                //  Non potendo contenere spazi nessun progetto si potra' chiamare " end"
                writer.write(" end");
                writer.newLine();
                writer.flush();
            }
            catch (IOException e){
                if (DEBUG) e.printStackTrace();
            }
        }
    }


    /**
     * Metodo per eliminare un progetto
     * @param projectName Nome del progetto da eliminare
     * @return il codice di stato associato all'esito della richiesta (sara' compito di run() inviarlo al client)
     */
    private String cancelProject(String projectName) {
        //Codici di stato:
        //  Progetto cancellato con successo: 0
        //  Errore di formattazione: -1
        //  Progetto inesistente: -2
        //  Non sei membro del progetto: -3
        //  Non tutte le cards in DONE: -4
        //  Serializzazione fallita : -5

        projectName = projectName.replaceAll(" ", "_").replaceAll("[^\\w.\\-_]", "");
        if (projectName.length()==0 || projectName.contains(" ")) return "-1";
        //Creo un file con path la directory del rpogetto
        File projDir = new File(PATHDIRPROJECTS + "/" + projectName);
        synchronized (progetti) {
            Progetto tmpProgetto = progetti.get(projectName);
            if (!progetti.containsKey(projectName)) return "-2";
            if (!tmpProgetto.getMembers().contains(currentUser)) return "-3";
            //Controllo che tutte le card abbiano status DONE
            if (!tmpProgetto.allDONE()) return "-4";
            try {
                projectsDB.getProgetti().remove(tmpProgetto);
                progetti.remove(projectName);
                mapper.writeValue(PATHJSONPROJECTS, projectsDB);
                for (String user : tmpProgetto.getMembers()) {
                    updater.makeUnJoin(user, projectName);
                }
                //Cancello ricorsivamente i file delle cards
                for (File f : projDir.listFiles()) {
                    f.delete();
                }
                projDir.delete();
            } catch (IOException e) {
                if(DEBUG) e.printStackTrace();
                projectsDB.getProgetti().add(tmpProgetto);
                progetti.put(projectName, tmpProgetto);
                return "-5";
            }
            return "0";
        }
    }

    //UTENTI------------------------------------------------------------------------------------------------------------
    /**
     * Metodo per aggiungere un utente a un progetto
     * @param rawinput Input da parsare e ottenere projectName e userName
     * @return il codice di stato associato all'esito della richiesta (sara' compito di run() inviarlo al client)
     */
    private String addMember(String rawinput) {
        //Codici di stato:
        //  Aggiunta effettuata con successo: 0
        //  Errore di formattazione: -1
        //  Utente non registrato: -2
        //  Progetto non presente: -3
        //  Richiedente non membro del progetto: -4
        //  Utente gia' membro del progetto: -5
        //  Serializzazione fallita: -6

        String projectName, userName;
        try {
            String[] inputTokenized = rawinput.trim().split("\\s+");
            projectName = inputTokenized[0];
            userName = inputTokenized[1];
        } catch (IndexOutOfBoundsException e) {
            return "-1";
        }
        if (projectName.length() == 0 || userName.length() == 0) return "-1";
        synchronized (users){
            if (!users.containsKey(userName)) return "-2";
        }
        synchronized (progetti) {
            if (!progetti.containsKey(projectName)) return "-3";
            Progetto tmpProgetto = progetti.get(projectName);
            if (!tmpProgetto.getMembers().contains(currentUser)) return "-4";
            if (tmpProgetto.getMembers().contains(userName)) return "-5";
            tmpProgetto.getMembers().add(userName);
            try {
                mapper.writeValue(PATHJSONPROJECTS, projectsDB);
                updater.makeJoin(userName, projectName, tmpProgetto.getChatAdd());
            } catch (Exception e) {
                //Se fallisco la serializzazione ripristino i cambiamenti e ritorno un codice di erorre
                if(DEBUG) e.printStackTrace();
                progetti.get(projectName).getMembers().remove(userName);
                return "-6";
            }
        }
        return "0";
    }

    /**
     * Metodo ottenere l'elenco dei membri di un progetto
     * @param projectName Nome del progetto di cui ottenere i membri
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void showMembers(String projectName, BufferedWriter writer) {
        //Codici di stato:
        //  Successo: 0
        //  Errore di formattazione: -1
        //  Progetto non presente: -2
        //  Richiedente non membro del progetto: -3

        projectName = projectName.trim();
        try {
            if (projectName.contains(" ")) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            synchronized (progetti) {
                if (!progetti.containsKey(projectName)) {
                    writer.write("-2");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                Progetto tmpProgetto = progetti.get(projectName);
                if (!tmpProgetto.getMembers().contains(currentUser)) {
                    writer.write("-3");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                writer.write("0");
                writer.newLine();
                for (String member : tmpProgetto.getMembers()) {
                    writer.write(member);
                    writer.newLine();
                }
            }
            //Stringa standardizzata di terminazione elenco
            //  Non potendo contenere spazi nessun utente si potra' chiamare " end"
            writer.write(" end");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
    }

    //CARDS-------------------------------------------------------------------------------------------------------------
    /**
     * Metodo per aggiungere una nuova card a un progetto
     * @param rawinput Input da parsare e ottenere projectName, cardName e descrizione
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void addCard(String rawinput, BufferedWriter writer) {
        //Codici di stato:
        //  Aggiunta effettuata con successo: 0
        //  Errore di formattazione: -1
        //  Progetto non presente: -2
        //  Richiedente non membro del progetto: -3
        //  Card gia' presente: -4
        //  Serializzazione fallita: -5
        //  Impossibile comunicare con la chat: -6

        try {
            String projectName, cardName, description;
            try {
                String[] inputTokenized = rawinput.trim().split("\\s+");
                projectName = inputTokenized[0].replaceAll("[^\\w.-]", "");
                cardName = inputTokenized[1].replaceAll("[^\\w.-]", "");
                description = inputTokenized[2];
            } catch (IndexOutOfBoundsException e) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }

            if (projectName.length() == 0 || cardName.length() == 0 || description.length() == 0) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            Card tmpCard = new Card(cardName, description, projectName);
            synchronized (progetti) {
                //Controlli
                if (!progetti.containsKey(projectName)){
                    writer.write("-2");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }

                Progetto tmpProgetto = progetti.get(projectName);

                if (!tmpProgetto.getMembers().contains(currentUser)){
                    writer.write("-3");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                if (tmpProgetto.getCards().containsKey(cardName)){
                    writer.write("-4");
                    writer.newLine();
                    writer.write(cardName);
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                //Aggiungo la card al progetto
                tmpProgetto.getCards().put(cardName, tmpCard);
                try (FileOutputStream persistentCard = new FileOutputStream(PATHDIRPROJECTS + "/" + projectName + "/" + cardName)) {
                    mapper.writeValue(PATHJSONPROJECTS, projectsDB);
                    //Scrivo su file la lista di partenza
                    persistentCard.write(("TODO" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    //Se fallisco la serializzazione ripristino l'inserimento
                    if(DEBUG) e.printStackTrace();
                    tmpProgetto.getCards().remove(cardName);
                    writer.write("-5");
                    writer.newLine();
                    writer.flush();
                    return;
                }
                try {
                    //Notifico sulla chat l'aggiunta della card
                    InetAddress niAddress= InetAddress.getByName("127.0.0.1");
                    NetworkInterface ni = NetworkInterface.getByInetAddress(niAddress);
                    InetAddress group = InetAddress.getByName(tmpProgetto.getChatAdd());
                    InetSocketAddress target = new InetSocketAddress(group, MainServer.MULTICASTPORT);
                    DatagramChannel datagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                            .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                            .bind(new InetSocketAddress(MainServer.MULTICASTPORT))
                            .setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                    //Invio il messaggio sulla chat, non ho bisogno di effettuare la join
                    datagramChannel.send(ByteBuffer.wrap((projectName + ":" + currentUser + " ha inserito la card "+ cardName + " nella lista TODO").getBytes(StandardCharsets.UTF_8)), target);
                }
                catch (IOException e){
                    writer.write("-6");
                    writer.newLine();
                    writer.write(cardName);
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
            }
            writer.write("0");
            writer.newLine();
            writer.write(cardName);
            writer.newLine();
            writer.write(projectName);
            writer.newLine();
            writer.flush();
        }
        catch (IOException e){
            if(DEBUG) e.printStackTrace();
        }
    }

    /**
     * Metodo per mostrare le cards e le relative informazioni di un progetto
     * @param projectName Nome del progetto del quale ottenere le informazioni sulle cards
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void showCards(String projectName, BufferedWriter writer) {
        //Codici di stato:
        //  Aggiunta effettuata con successo: 0
        //  Errore di formattazione: -1
        //  Progetto non presente: -2
        //  Richiedente non membro del progetto: -3
        projectName = projectName.trim();
        try {
            if (projectName.contains(" ")) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            synchronized (progetti) {
                if (!progetti.containsKey(projectName)) {
                    writer.write("-2");
                    writer.newLine();
                    writer.flush();
                    return;
                }
                Progetto tmpProgetto = progetti.get(projectName);
                if (!tmpProgetto.getMembers().contains(currentUser)) {
                    writer.write("-3");
                    writer.newLine();
                    writer.flush();
                    return;
                }
                writer.write("0");
                writer.newLine();
                for (Card entry : tmpProgetto.getCards().values()) {
                    writer.write(entry.getNome());
                    writer.newLine();
                    writer.write(entry.getStatus());
                    writer.newLine();
                }
                //Stringa standardizzata di terminazione elenco
                //  Non potendo contenere spazi nessuna card si potra' chiamare " end"
                writer.write(" end\n");
                writer.flush();
            }
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
    }

    /**
     * Metodo per mostrare le informazioni della card di un progetto
     * @param rawinput Input da parsare e ottenere projectName e cardName
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void showCard(String rawinput, BufferedWriter writer) {
        //Codici di stato:
        //  Aggiunta effettuata con successo: 0
        //  Errore di formattazione: -1
        //  Progetto non presente: -2
        //  Richiedente non membro del progetto: -3
        //  Card non presente: -4

        String projectName, cardName;
        Card tmpCard;
        try {
            try {
                String[] inputTokenized = rawinput.trim().split("\\s+");
                projectName = inputTokenized[0];
                cardName = inputTokenized[1];
            } catch (IndexOutOfBoundsException e) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            synchronized (progetti) {
                if (!progetti.containsKey(projectName)) {
                    writer.write("-2");
                    writer.newLine();
                    writer.flush();
                    return;
                }
                Progetto tmpProgetto = progetti.get(projectName);
                if (!tmpProgetto.getMembers().contains(currentUser)) {
                    writer.write("-3");
                    writer.newLine();
                    writer.flush();
                    return;
                }
                tmpCard = tmpProgetto.getCards().get(cardName);
                if (tmpCard == null) {
                    writer.write("-4");
                    writer.newLine();
                    writer.flush();
                    return;
                }
            }
            writer.write("0");
            writer.newLine();
            writer.write(tmpCard.getNome());
            writer.newLine();
            writer.write(tmpCard.getDescrizione());
            writer.newLine();
            writer.write(tmpCard.getStatus());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
    }

    /**
     * Metodo per spostare una card all'interno delle liste di un progetto
     * @param rawinput Input da parsare e ottenere projectName e cardName
     * @return il codice di stato associato all'esito della richiesta (sara' compito di run() inviarlo al client)
     */
    private String moveCard(String rawinput) {
        //Codici di stato:
        //  Movimento effettuata con successo: 0
        //  Errore di formattazione: -1
        //  Progetto inesistente: -2
        //  Richiedente non membro del progetto: -3
        //  Card non presente nel progetto: -4
        //  Lista di partenza sconosciuto: -5
        //  Lista di destinazione sconosciuto: -6
        //  Card non appartenente alla lista di partenza indicata: -7
        //  Lista di partenza e di destinazione coincidenti: -8
        //  Vincolo tra liste non rispettato (vedere diagramma a stati finiti): -9

        String projectName, cardName, partenza, destinazione;
        try {
            String[] tokens = rawinput.trim().split("\\s+");
            projectName = tokens[0];
            cardName = tokens[1];
            //Porto partenza e destinazione in maiuscolo per uniformare le strighe per l'equals
            // e permettere l'inserimento non necessariamente in capslock da parte dell'utente
            partenza = tokens[2].toUpperCase(Locale.ROOT);
            destinazione = tokens[3].toUpperCase(Locale.ROOT);
        } catch (ArrayIndexOutOfBoundsException e) {
            return "-1";
        }

        boolean serialized=false;
        synchronized (progetti) {
            if (!progetti.containsKey(projectName)) return "-2";
            Progetto tmpProgetto = progetti.get(projectName);
            if (!tmpProgetto.getMembers().contains(currentUser)) return "-3";
            if (!tmpProgetto.getCards().containsKey(cardName)) return "-4";
            Card tmpCard = tmpProgetto.getCards().get(cardName);
            if (!(partenza.equals("TODO") || partenza.equals("INPROGRESS") || partenza.equals("TOBEREVISED") || partenza.equals("DONE")))
                return "-5";
            if (!(destinazione.equals("TODO") || destinazione.equals("INPROGRESS") || destinazione.equals("TOBEREVISED") || destinazione.equals("DONE")))
                return "-6";
            if (!tmpCard.getStatus().equals(partenza)) return "-7";
            if (partenza.equals(destinazione)) return "-8";
            if (partenza.equals("DONE")) return "-9";
            if (partenza.equals("TODO")) {
                switch (destinazione){
                    case "INPROGRESS": {
                        serialized=auxmoveCard(tmpCard, partenza, destinazione);
                        break;
                    }
                    default: return "-9";
                }
            }
            if (partenza.equals("INPROGRESS")) {
                switch (destinazione) {
                    case "TOBEREVISED": {
                        serialized=auxmoveCard(tmpCard, partenza, destinazione);
                        break;
                    }
                    case "DONE": {
                        serialized=auxmoveCard(tmpCard, partenza, destinazione);
                        break;
                    }
                    default: return "-9";
                }
            }
            if (partenza.equals("TOBEREVISED")) {
                switch (destinazione) {
                    case "DONE": {
                        serialized=auxmoveCard(tmpCard, partenza, destinazione);
                        break;
                    }
                    case "INPROGRESS": {
                        serialized=auxmoveCard(tmpCard, partenza, destinazione);
                        break;
                    }
                    default: return "-9";
                }
            }
            if(serialized){
                try {
                    //Ottengo l'indirizzo del gruppo multicast dal progetto
                    InetAddress group = InetAddress.getByName(tmpProgetto.getChatAdd());
                    //Setto di default la network interface di loopback
                    InetAddress niAddress= InetAddress.getByName("127.0.0.1");
                    NetworkInterface ni = NetworkInterface.getByInetAddress(niAddress);
                    InetSocketAddress address = new InetSocketAddress(group, MainServer.MULTICASTPORT);
                    DatagramChannel datagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                            .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                            .bind(new InetSocketAddress(MainServer.MULTICASTPORT))
                            .setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                    //Invio il messaggio sulla chat, non ho bisogno di effettuare la join
                    datagramChannel.send(ByteBuffer.wrap((projectName + ":" + currentUser + " ha spostato la card " + cardName + " da " + partenza + " a " + destinazione).getBytes(StandardCharsets.UTF_8)), address);
                }
                catch (IOException e){
                    return "-10";
                }
            }
        }
        return "0";
    }

    /**
     * Metodo ausiliario per la serializzazione dello spostamento di una card all'interno delle liste di un progetto
     * @param card Oggetto Card della quale modificare la lista
     * @param partenza Lista di partenza
     * @param destination Lista di destinazione
     * @return true se la serializzazione e' effettuata con successo, false altrimenti
     */
    private boolean auxmoveCard(Card card, String partenza, String destination) {
        card.setStatus(destination);
        //Setto a true il flag "append" di FileOutputStream in modo da scrivere sul file in append i nuovi status della card
        try (FileOutputStream persistentCard = new FileOutputStream(PATHDIRPROJECTS + "/" + card.getProgetto() + "/" + card.getNome(), true)) {
            mapper.writeValue(PATHJSONPROJECTS, projectsDB);
            persistentCard.write((destination + "\r\n").getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
            //Se fallisco la serializzazione ripristino la lista di partenza
            card.setStatus(partenza);
            return false;
        }
    }

    /**
     * Metodo per mostrare la storia di una card
     * @param rawinput Input da parsare e ottenere projectName e cardName
     * @param writer Stream di caratteri bufferizzato sul quale inviare le informazioni al client
     */
    private void getCardHistory(String rawinput, BufferedWriter writer) {
        //Codici di stato:
        //  Storico recuperato con successo: 0
        //  Errore di formattazione: -1
        //  Progetto non presente: -2
        //  Richiedente non membro del progetto: -3
        //  Card non presente: -4

        try {
            String projectName, cardName;
            try {
                String[] inputTokenized = rawinput.trim().split("\\s+");
                projectName = inputTokenized[0];
                cardName = inputTokenized[1];
            } catch (IndexOutOfBoundsException e) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            if (projectName.length() == 0 || cardName.length() == 0) {
                writer.write("-1");
                writer.newLine();
                writer.flush();
                return;
            }
            synchronized (progetti) {
                if (!progetti.containsKey(projectName)) {
                    writer.write("-2");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                Progetto tmpProgetto = progetti.get(projectName);
                if (!tmpProgetto.getMembers().contains(currentUser)) {
                    writer.write("-3");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                Card tmpCard = tmpProgetto.getCards().get(cardName);
                if (tmpCard == null) {
                    writer.write("-4");
                    writer.newLine();
                    writer.write(projectName);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                writer.write("0");
                writer.newLine();
                String res;
                //Apro un nuovo FileReader wrappandolo in un BufferedReader con path il file della card della quale ottenere la storia
                try (BufferedReader persistentCard = new BufferedReader(new FileReader(PATHDIRPROJECTS + "/" + projectName + "/" + cardName))) {
                    while ((res = persistentCard.readLine()) != null) {
                        writer.write(res);
                        writer.newLine();
                    }
                    //Stringa standardizzata di terminazione elenco
                    //  Nessuna lista si potra' chiamare " end"
                    writer.write(" end");
                    writer.newLine();
                    writer.flush();
                } catch (IOException e) {
                    if(DEBUG) e.printStackTrace();
                }
            }
        } catch (IOException e) {
            if(DEBUG) e.printStackTrace();
        }
    }
}