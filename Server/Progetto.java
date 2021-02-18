import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * @author Tommaso Lencioni 560309
 */
public class Progetto implements Serializable {
    private String nome;
    private HashMap<String,Card> cards;
    private LinkedList<String> members;
    private String chatAdd;

    //Costruttore di default richiesto da Jackson
    Progetto(){}

    Progetto(String n){
        this.nome=n;
        cards= new HashMap<>();
        members= new LinkedList<>();
    }

    public String getNome() {return nome;}
    public LinkedList<String> getMembers() {return members;}
    public HashMap<String,Card> getCards() { return cards;}
    public String getChatAdd(){return chatAdd;}

    public void setNome(String n) {nome = n;}
    public void setMembers(LinkedList<String> m) {members = m;}
    public void setCards(HashMap<String, Card> c) {cards = c;}
    public void setChatAdd(String a){chatAdd=a;}

    public boolean allDONE(){
        for(Card c: cards.values()){
            if (!c.getStatus().equals("DONE")) return false;
        }
        return true;
    }
}
