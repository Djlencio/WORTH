import java.io.IOException;
import java.net.*;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.rmi.*;
import java.rmi.server.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Tommaso Lencioni 560309
 */
public class ClientUpdater extends RemoteObject implements ClientUpdaterInterface {
    protected static final int MULTICASTPORT=MainClient.MULTICASTPORT;

    //HashMap di utenti col loro stato (true=online, false=offline)
    private static HashMap<String, Boolean> allUsers=MainClient.allUsers;
    //Hashmap di chat con key nome del progetto e una linked list di String che contiene i messaggi
    ConcurrentHashMap<String, LinkedList<String>> chats=MainClient.chats;
    //HashMap di indirizzi multicast indicizzati col nome del progetto
    ConcurrentHashMap<String, String> multicastGroups = MainClient.multicastGroups;
    //Linkedlist di InetAddress sui quali il datagramchannel deve ancora effettuare la join
    private LinkedList<InetAddress> toJoin;
    //Thrad che effettua demultiplexing NIO sui datagrammi in arrivo
    private Thread threadListener;
    //Network Interface per l'invio e la ricezione di datagrammi UDP multicast
    private static NetworkInterface ni=MainClient.ni;
    //Scoket alla quale inviare i messaggi di wakeup
    private static InetSocketAddress wakeupSocket;
    //DatagramChannel per l'invio di messaggi di wakeup, non occorre effettuare la join per l'invio
    private static DatagramChannel wakeupDatagramChannel;

    public ClientUpdater() throws IOException{
        super();
        //Indirizzo di deafult per i messaggi di wakeup
        InetAddress wakeupGroup= InetAddress.getByName("225.0.0.0");
        wakeupSocket = new InetSocketAddress(wakeupGroup, MULTICASTPORT);
        //Apro il DatagramChannel su IPv4, permetto il riuso,
        // faccio il bind sulla porta multicast e setto l'interfaccia ottenuta sopra
        wakeupDatagramChannel= DatagramChannel.open(StandardProtocolFamily.INET)
                .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                .bind(new InetSocketAddress(MULTICASTPORT))
                .setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
        toJoin=new LinkedList<>();
        Listener listener = new Listener();
        threadListener= new Thread(listener);
        threadListener.start();
    }

    /**
     * Metodo invocato dal server per aggiornare con lo stato dell'utente in input l'HashMap interna ad ogni client
     * @param nome Nome uente del quale aggiornare lo stato
     * @param status Stato con il quale aggiornare l'utente specificato
     * @throws RemoteException in caso di problemi con l'oggetto remoto
     */
    public void notifyStatus(String nome, boolean status) throws RemoteException {
        allUsers.put(nome, status);
    }

    /**
     * Metodo per effettuare il join di una chat di progetto
     * @param projectName nome del progetto del quale joinare la chat
     * @param multicastAddress indirizzo di multicast della chat di progetto
     * @throws RemoteException in caso di problemi con l'oggetto remoto
     */
    public synchronized void joinChat(String projectName, String multicastAddress) throws RemoteException {
        try {
            chats.put(projectName, new LinkedList<>());
            multicastGroups.put(projectName, multicastAddress);
            toJoin.add(InetAddress.getByName(multicastAddress));
            wakeup();
        }
        catch (UnknownHostException e){
            System.err.println("* Problemi con l'indirizzo della chat del progetto");
            if(MainClient.DEBUG) e.printStackTrace();
        }
    }

    /**
     * Metodo per disiscriversi dalla una chat di progetto (in seguito alla sua cancellazione)
     * @param projectName nome del progetto dal quale disiscriversi
     * @throws RemoteException in caso di problemi con l'oggetto remoto
     */
    public void unjoinChat(String projectName) throws RemoteException {
        //Non faccio la close dalla chat cancellata ma la rimuovo solo dalle strutture dati cosi' provando a leggerla dara' null
        //  perche' durante la sessione del server (quindi anche dei client) non potra' essere creato un
        //  progetto con lo stesso indirizzo di multicast del progetto cancellato
        chats.remove(projectName);
        multicastGroups.remove(projectName);
    }

    public synchronized void unjoinAll() throws RemoteException {
        //Interrompo il listener e lancio un wakeup
        threadListener.interrupt();
        wakeup();
    }

    public void wakeup(){
        try{
            //Invio il messaggio di wakeup
            wakeupDatagramChannel.send(ByteBuffer.wrap(".".getBytes(StandardCharsets.UTF_8)), wakeupSocket);
        }
        catch (IOException e){
            System.err.println("* Un errore e' avvenuto nell'invio di wakeup");
            if(MainClient.DEBUG) e.printStackTrace();
        }
    }

    private class Listener implements Runnable {
        @Override
        public void run() {
            try {
                Selector selector = Selector.open();
                //Indirizzo del gruppo di wakeup
                InetAddress group = InetAddress.getByName("225.0.0.0");
                //Apro il DatagramChannel su IPv4, permetto il riuso,
                // faccio il bind sulla porta multicast e setto l'interfaccia ottenuta sopra
                DatagramChannel datagramChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                        .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                        .bind(new InetSocketAddress(MULTICASTPORT)).setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                //Configuro il channel in modalita' non bloccante
                datagramChannel.configureBlocking(false);
                //Mi unisco al gruppo per ricezione di datagrammi di wakeup
                datagramChannel.join(group, ni);
                //Registro il canale al Selector in read
                datagramChannel.register(selector, SelectionKey.OP_READ);
                //Alloco un Bytebuffer fuori dall'heap della JVM (piu' efficiente ma anche piu' costoso di un bytebuffer non-direct
                ByteBuffer bb = ByteBuffer.allocateDirect(100);
                while (true) {
                    //Se il listener e' stato interrotto returno
                    if(Thread.currentThread().isInterrupted()) return;
                    //Faccio lo spool della lista toJoin per far joinare al mio datagramchannel tutti i progetti dei quali
                    //  sono membro ma non ho ancora joinato la chat
                    while(!toJoin.isEmpty()){
                        datagramChannel.join(toJoin.pollFirst(), ni);
                    }
                    if (selector.select() == 0) {
                        continue;
                    }
                    //Itero sulle selection keys
                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        //Se e' readable
                        if (key.isReadable()) {
                            DatagramChannel dc = (DatagramChannel) key.channel();
                            //Ricevo i dati dal channel e li immagazzino sul bytebuffer
                            dc.receive(bb);
                            //Flippo il bytebuffer per metterlo in modalita' lettura
                            bb.flip();
                            //Inizializzo scrivo sull'array di bytes i data del bytebuffer
                            byte[] data = new byte[bb.limit()];
                            bb.get(data);
                            //Creao una stringa che corrispondera' al messaggio ricevuto
                            String res=new String(data, StandardCharsets.UTF_8);
                            //Controllo che non sia un messaggio di wakeup
                            if(!res.equals(".")){
                                //Parso la stringa ottenendo il nome del progetto e il messaggio
                                String projectName = res.substring(0,res.indexOf(":"));
                                String message = res.substring(res.indexOf(":")+1).trim();
                                //Il progetto potrebbe essere cancellato mentre sto per inserirvi un messaggio mandato in precedenza
                                //  Controllo quindi che tmpChat non sia null
                                LinkedList<String> tmpChat=chats.get(projectName);
                                if(tmpChat!=null) {
                                    //Sincronizzando la lista locale del progetto inserisco il messaggio ricevuto
                                    synchronized (tmpChat) {
                                        chats.get(projectName).addLast(message);
                                    }
                                }
                            }
                            //Pulisco il buffer per metterlo in modalita' scrittura
                            bb.clear();
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("* Un errore e' avvenuto nella ricezione di un messaggio");
                if(MainClient.DEBUG) e.printStackTrace();
            }
        }
    }
}