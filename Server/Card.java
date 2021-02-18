import java.io.Serializable;

/**
 * @author Tommaso Lencioni 560309
 */
public class Card implements Serializable {
    private String nome, descrizione, progetto, status;

    //Costruttore di default richiesto da Jackson
    Card(){}

    //Contruttore
    Card(String n, String d, String p){
        nome=n;
        descrizione=d;
        progetto=p;
        //Tutte le cards partono dalla lista TODO
        status="TODO";
    }

    //Metodi setter
    public void setNome(String n) {nome = n;}
    public void setDescrizione(String d) {descrizione = d;}
    public void setStatus(String s) {status = s;}
    public void setProgetto(String p) {progetto = p;}
    
    //Metodi getter
    public String getNome() {return nome;}
    public String getDescrizione() {return descrizione;}
    public String getStatus() {return status;}
    public String getProgetto(){return progetto;}
}
