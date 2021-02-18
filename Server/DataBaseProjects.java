import java.util.LinkedList;

/**
 * @author Tommaso Lencioni 560309
 */
public class DataBaseProjects {
    private LinkedList<Progetto> progetti;

    DataBaseProjects(){
        progetti =new LinkedList<>();
    }

    public void setProgetti(LinkedList<Progetto> progetti) {
        this.progetti = progetti;
    }
    public LinkedList<Progetto> getProgetti (){return progetti;}
}
