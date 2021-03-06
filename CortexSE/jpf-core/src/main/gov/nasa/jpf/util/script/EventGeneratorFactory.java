package gov.nasa.jpf.util.script;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.ListenerAdapter;
import gov.nasa.jpf.jvm.ChoiceGenerator;
import gov.nasa.jpf.jvm.SystemState;
import gov.nasa.jpf.search.Search;
import gov.nasa.jpf.util.DynamicObjectArray;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * abstract root for backtrackable event generator factories
 *
 * <2do> - we don't support backtracking for sections yet! needs to be implemented for
 * state charts
 */
public abstract class EventGeneratorFactory extends ListenerAdapter
                                         implements ElementProcessor, Iterable<EventGenerator> {

  static final String DEFAULT = "default";

  // helper class to store our internal state. For a simple script based system,
  // storing the 'cur' index (into the queue) would do, but the queue might have been
  // generated dynamically, so we need some container to store both
  static class Memento {
    ArrayList<EventGenerator> queue;
    int cur; // cursor into queue

    Memento (EventGeneratorFactory fact) {
      queue = fact.queue;
      cur = fact.cur;
    }

    void restore (EventGeneratorFactory fact) {
      fact.queue = queue;
      fact.cur = cur;
    }
  }


  // <2do> this is lame - if we really want 'instructions' in the queue, rather
  // than data (== CGs), then we should have a queue of general EventOp entries
  // this is only used for unbounded REPEATs so far
  // <2do> the nextCG is currently unconditionally reset in getNextChoiceGenerator()
  // so we have to make sure we don't jump back if the jump target state was already
  // visited, which we have to store in the Jump
  static class Loop extends EventGenerator {

    int startPos, endPos;

    Loop (String id, int startPos,  int endPos){
      super(id);
      
      this.startPos = startPos;
      this.endPos = endPos;
    }

    int getStartPos() {
      return startPos;
    }

    //--- those are all dummies - this isn't really a choice
    public void advance() {}

    public Class getChoiceType() {
      return null;
    }

    public Object getNextChoice() {
      return null;
    }

    public int getProcessedNumberOfChoices() {
      return 0;
    }

    public int getTotalNumberOfChoices() {
      return 0;
    }

    public boolean hasMoreChoices() {
      return false;
    }

    public ChoiceGenerator randomize() {
      return null;
    }

    public void reset() {}

  }

  /** the last returned position in the generator stream */
  protected int cur;

  /** this is where we store the cur positions for backtracking and restoring states */
  DynamicObjectArray<Memento> states;

  protected String scriptFileName;
  protected Script script;
  protected Config conf;

  protected LinkedHashMap<String,ArrayList<EventGenerator>> sections;
  protected ArrayList<EventGenerator> queue;

  EventFactory efact;

  protected EventGeneratorFactory () {
    efact = null;
  }

  protected EventGeneratorFactory (EventFactory efact) {
    this.efact = efact;
  }

  protected void init (String fname) throws ESParser.Exception {
    cur = 0;
    states = new DynamicObjectArray<Memento>();

    sections = new LinkedHashMap<String,ArrayList<EventGenerator>>();
    queue = new ArrayList<EventGenerator>();
    sections.put(DEFAULT, queue);

    ESParser parser= new ESParser(fname, efact);
    script = parser.parse();
    scriptFileName = fname;

    script.process(this);
  }

  public Iterator<EventGenerator> iterator() {
    return queue.iterator();
  }

  protected void addLoop (int startPos) {
    queue.add( new Loop( "loop", startPos, queue.size()-1));
  }

  public abstract Class<?> getEventType();

  /**
   * reset the enumeration state of this factory
   */
  public void reset () {
    cur = 0;
  }

  public String getScriptFileName() {
    return scriptFileName;
  }

  public Script getScript() {
    return script;
  }

  public boolean hasSection (String id) {
    return sections.containsKey(id);
  }

  public ArrayList<EventGenerator> getSection (String id) {
    return sections.get(id);
  }

  public ArrayList<EventGenerator> getDefaultSection () {
    return sections.get(DEFAULT);
  }

  protected void setQueue (ArrayList<EventGenerator> q) {
    if (queue != q) {
      queue = q;
      cur = 0;
    }
  }

  protected EventGenerator getNextEventGenerator() {
    EventGenerator cg;
    int n = queue.size();

    if (n == 0) {
      return null; // nothing to do
    }

    if (cur < n) {
      cg = getQueueItem(cur); // might clone the queue item

      // <2do> - this is a BAD hot fix, but it's going away soon!
      if (cg instanceof Loop) {
        int tgtPos = ((Loop)cg).getStartPos();
        cg = queue.get(tgtPos);

        if (!cg.hasMoreChoices()) {
          for (int i=tgtPos; i<cur; i++) {
            queue.get(i).reset();
          }
        }

        cur = tgtPos;
      }

      cg.setId(Integer.toString(cur));

      // might be reused if we re-enter a section sequence or REPEAT body, so we have to reset
      // <2do> - commenting this out leads to premature state matching on model loops
      // (will be fixed by the revamped environment modeling)
      //cg.reset();

      cur++;
      return cg;

    } else {
      return null; // nothing left
    }
  }

  // we encapsulate this because it might require cloning
  protected EventGenerator getQueueItem (int i) {
    return queue.get(i);
  }


  public int getTotalNumberOfEvents() {
    int total=0;
    int last = 1;

    for (EventGenerator cg : queue) {
       int level = cg.getTotalNumberOfChoices() * last;
       total += level;
       last = level;
    }

    return total;
  }

  public void printOn (PrintWriter pw) {
    for (EventGenerator eg : queue) {
      pw.println(eg);
    }
  }

  /************************************** SearchListener interface **************/
  /* we need this after a backtrack and restore to determine the next CG to return
   */

  public void searchStarted (Search search) {
    cur = 0;
  }

  public void stateAdvanced (Search search) {
    int idx = search.getStateId();

    if (idx >= 0) { // <??> why would it be notified for the init state?
      Memento m = new Memento(this);
      states.set(idx, m);
    }
  }

  public void stateBacktracked (Search search) {
    Memento m = states.get(search.getStateId());
    m.restore(this);
    // nextCg will be re-computed (->getNext), so there is no need to reset
  }

  public void stateRestored (Search search) {
    Memento m = states.get(search.getStateId());
    m.restore(this);

    // nextCg is restored (not re-computed), so we need to reset
    SystemState ss = search.getVM().getSystemState();
    ChoiceGenerator cgNext = ss.getNextChoiceGenerator();
    cgNext.reset();
  }

}
