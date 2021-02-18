import java.io.Serializable;

/**
 * @author Tommaso Lencioni 560309
 */
public class Utente implements Serializable {
    private String nome;
    private String password;

    //Costruttore di default richiesto da Jackson
    Utente(){}

    //Costruttore
    Utente(String n, String p){
        this.nome=n;
        this.password=p;
    }

    //Metodi setter
    public void setNome(String n) { this.nome = n; }
    public void setPassword(String p) { this.password = p;}

    //Metodi getter
    public String getNome() {
        return nome;
    }
    public String getPassword(){ return password;}
}
