import java.util.ArrayList;

/**
 * @author Tommaso Lencioni 560309
 */
public class DataBaseUsers {
    private ArrayList<Utente> utenti;

    DataBaseUsers(){
        utenti =new ArrayList<>();
    }

    public void setUtenti(ArrayList<Utente> utenti) {
        this.utenti = utenti;
    }
    public ArrayList<Utente> getUtenti(){
        return utenti;
    }

    public void addUser(Utente u){
        utenti.add(u);
    }
}
