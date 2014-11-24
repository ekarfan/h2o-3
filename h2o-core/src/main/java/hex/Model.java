package hex;

import water.*;
import water.api.ModelSchema;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.TransfVec;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * A Model models reality (hopefully).
 * A model can be used to 'score' a row (make a prediction), or a collection of
 * rows on any compatible dataset - meaning the row has all the columns with the
 * same names as used to build the mode and any enum (categorical) columns can
 * be adapted.
 */
public abstract class Model<M extends Model<M,P,O>, P extends Model.Parameters, O extends Model.Output> extends Lockable<M> {
  Model( Key selfkey ) { super(selfkey); }

  /** Different prediction categories for models.  NOTE: the values list in the API annotation ModelOutputSchema needs to match. */
  public static enum ModelCategory {
    Unknown,
    Binomial,
    Multinomial,
    Regression,
    Clustering
  }

  /** Needs to be set correctly otherwise eg scoring does not work.
   *  @return true if there was a response column used during training. */
  public boolean isSupervised() { return false; }

  /** Model-specific parameter class.  Each model sub-class contains an
   *  instance of one of these containing its builder parameters, with
   *  model-specific parameters.  E.g. KMeansModel extends Model and has a
   *  KMeansParameters extending Model.Parameters; sample parameters include K,
   *  whether or not to normalize, max iterations and the initial random seed.
   *
   *  <p>The non-transient fields are input parameters to the model-building
   *  process, and are considered "first class citizens" by the front-end - the
   *  front-end will cache Parameters (in the browser, in JavaScript, on disk)
   *  and rebuild Parameter instances from those caches.  
   */
  public abstract static class Parameters extends Iced {
    public Key _destination_key;     // desired Key for this model (otherwise is autogenerated)
    public Key _train;               // User-Key of the Frame the Model is trained on
    public Key _valid;               // User-Key of the Frame the Model is validated on, if any
    // TODO: This field belongs in the front-end column-selection process and
    // NOT in the parameters - because this requires all model-builders to have
    // column strip/ignore code.
    public String[] _ignored_columns;// column names to ignore for training
    public boolean _dropNA20Cols;    // True if dropping cols > 20% NAs

    // Scoring a model on a dataset is not free; sometimes it is THE limiting
    // factor to model building.  By default, partially built models are only
    // scored every so many major model iterations - throttled to limit scoring
    // costs to less than 10% of the build time.  This flag forces scoring for
    // every iteration, allowing e.g. more fine-grained progress reporting.
    public boolean _score_each_iteration;

    // Public no-arg constructor for reflective creation
    public Parameters() { _dropNA20Cols = defaultDropNA20Cols(); }

    /** @return the training frame instance */
    public final Frame train() { return _train.get(); }
    /** @return the validation frame instance, or the training frame
     *  if a validation frame was not specified */
    public final Frame valid() { return _valid==null ? train() : _valid.<Frame>get(); }

    /** Read-Lock both training and validation User frames. */
    public void lock_frames( Job job ) { 
      train().read_lock(job._key);
      if( _valid != null && !_train.equals(_valid) )
        valid().read_lock(job._key);
    }

    /** Read-UnLock both training and validation User frames. */
    public void unlock_frames( Job job ) {
      Frame tr = train();
      if( tr != null ) tr.unlock(job._key);
      if( _valid != null && !_train.equals(_valid) )
        valid().unlock(job._key);
    }

    // Override in subclasses to change the default; e.g. true in GLM
    protected boolean defaultDropNA20Cols() { return false; }
    
    public long checksum() {
      long field_checksum = 1L;

      Field field = null; // keep around in case of exception
      try {
        for (Field f : this.getClass().getFields()) {
          field = f;
          Object v = field.get(this);
          if (null != v) {
            int c = v.hashCode();
            field_checksum *= (c == 0 ? 17 : c);
          }
        }
      }
      catch (IllegalAccessException e) {
        throw H2O.fail("Caught IllegalAccessException accessing field: " + field.toString() + " while creating checksum for: " + this.toString());
      }

      return (field_checksum == 0 ? 13 : field_checksum) *
        train().checksum() *
        (_valid == null ? 17 : valid().checksum()) *
        (null == _ignored_columns ? 23: Arrays.hashCode(_ignored_columns));
    }
  }

  public P _parms; // TODO: move things around so that this can be protected

  public String [] _warnings = new String[0];

  public void addWarning(String s){
    _warnings = Arrays.copyOf(_warnings,_warnings.length+1);
    _warnings[_warnings.length-1] = s;
  }

  /** Model-specific output class.  Each model sub-class contains an instance
   *  of one of these containing its "output": the pieces of the model needed
   *  for scoring.  E.g. KMeansModel has a KMeansOutput extending Model.Output
   *  which contains the clusters.  The output also includes the names, domains
   *  and other fields which are determined at training time.  */
  public abstract static class Output extends Iced {
    /** Columns used in the model and are used to match up with scoring data
     *  columns.  The last name is the response column name (if any). */
    public String _names[];
    /** Returns number of input features (OK for most unsupervised methods, need to override for supervised!) */
    public int nfeatures() { return _names.length; }

    /** Categorical/factor/enum mappings, per column.  Null for non-enum cols.
     *  Columns match the post-init cleanup columns.
     *  The last column holds the response col enums for SupervisedModels.  */
    public String _domains[][];

    /** List of all the associated ModelMetrics objects, so we can delete them when we delete this model. */
    public Key[] _model_metrics = new Key[0];

    /** Job state (CANCELLED, FAILED, DONE).  TODO: Really the whole Job
     *  (run-time, etc) but that has to wait until Job is split from
     *  ModelBuilder. */
    public Job.JobState _state;

    /** Any final prep-work just before model-building starts, but after the
     *  user has clicked "go".  E.g., converting a response column to an enum
     *  touches the entire column (can be expensive), makes a parallel vec
     *  (Key/Data leak management issues), and might throw IAE if there are too
     *  many classes. */
    public Output( ModelBuilder b ) {
      if( b.error_count() > 0 )
        throw new IllegalArgumentException(b.validationErrors());
      // Capture the data "shape" the model is valid on
      _names  = b._train.names  ();
      _domains= b._train.domains();
    }

    /** The names of all the columns, including the response column (which comes last). */
    public String[] allNames() { return _names; }
    /** The name of the response column (which is always the last column). */
    public String responseName() { return   _names[  _names.length-1]; }
    /** The names of the levels for an enum (categorical) response column. */
    public String[] classNames() { return _domains[_domains.length-1]; }
    /** Is this model a classification model? (v. a regression or clustering model) */
    public boolean isClassifier() { return classNames() != null ; }
    public int nclasses() {
      String cns[] = classNames();
      return cns==null ? 1 : cns.length;
    }

    // Note: Clustering algorithms MUST redefine this method to return ModelCategory.Clustering:
    public ModelCategory getModelCategory() {
      return (isClassifier() ?
              (nclasses() > 2 ? ModelCategory.Multinomial : ModelCategory.Binomial) :
              ModelCategory.Regression);
    }

    protected void addModelMetrics(ModelMetrics mm) {
      _model_metrics = Arrays.copyOf(_model_metrics, _model_metrics.length + 1);
      _model_metrics[_model_metrics.length - 1] = mm._key;
    }

    public long checksum() {
      return (null == _names ? 13 : Arrays.hashCode(_names)) *
              (null == _domains ? 17 : Arrays.hashCode(_domains)) *
              getModelCategory().ordinal();
    }
  } // Output

  public O _output; // TODO: move things around so that this can be protected

  /**
   * Model-specific state class.  Each model sub-class contains an instance of one of
   * these containing its internal state: all the data that's required to, for example,
   * reload the state of the model to continue training.
   * TODO: use this!
  public abstract static class State<M extends Model<M,S>, S extends State<M,S>> extends Iced {
  }
  // TODO: make this an instance of a *parameterized* State class. . .
  State _state;
  public State getState() { return _state; }
   */

  /** The start time in mS since the epoch for model training. */
  public long training_start_time = 0L;

  /** The duration in mS for model training. */
  public long training_duration_in_ms = 0L;

  /**
   * Externally visible default schema
   * TODO: this is in the wrong layer: the internals should not know anything about the schemas!!!
   * This puts a reverse edge into the dependency graph.
   */
  public abstract ModelSchema schema();

  /** Full constructor */
  public Model( Key selfKey, P parms, O output) {
    super(selfKey);
    _parms  = parms ;  assert parms  != null;
    _output = output;  assert output != null;
  }

  public void start_training(long training_start_time) {
    Log.info("setting training_start_time to: " + training_start_time + " for Model: " + this._key.toString() + " (" + this.getClass().getSimpleName() + "@" + System.identityHashCode(this) + ")");

    final long t = training_start_time;
    new TAtomic<Model>() {
      @Override public Model atomic(Model m) {
          if (m != null) {
            m.training_start_time = t;
          } return m;
      }
    }.invoke(_key);
    this.training_start_time = training_start_time;
  }
  public void start_training(Model previous) {
    training_start_time = System.currentTimeMillis();
    Log.info("setting training_start_time to: " + training_start_time + " for Model: " + this._key.toString() + " (" + this.getClass().getSimpleName() + "@" + System.identityHashCode(this) + ") [checkpoint case]");
    if (null != previous)
      training_duration_in_ms += previous.training_duration_in_ms;

    final long t = training_start_time;
    final long d = training_duration_in_ms;
    new TAtomic<Model>() {
      @Override public Model atomic(Model m) {
          if (m != null) {
            m.training_start_time = t;
            m.training_duration_in_ms = d;
          } return m;
      }
    }.invoke(_key);
  }
  public void stop_training() {
    training_duration_in_ms += (System.currentTimeMillis() - training_start_time);
    Log.info("setting training_duration_in_ms to: " + training_duration_in_ms + " for Model: " + this._key.toString() + " (" + this.getClass().getSimpleName() + "@" + System.identityHashCode(this) + ")");

    final long d = training_duration_in_ms;
    new TAtomic<Model>() {
      @Override public Model atomic(Model m) {
          if (m != null) {
            m.training_duration_in_ms = d;
          } return m;
      }
    }.invoke(_key);
  }

  /** Bulk score for given <code>fr</code> frame.
   * The frame is always adapted to this model.
   *
   * @param fr frame to be scored
   * @return frame holding predicted values
   *
   * @see #score(Frame, boolean)
   */
  public Frame score(Frame fr) {
    return score(fr, true);
  }
  /** Bulk score the frame <code>fr</code>, producing a Frame result; the 1st Vec is the
   *  predicted class, the remaining Vecs are the probability distributions.
   *  For Regression (single-class) models, the 1st and only Vec is the
   *  prediction value.
   *
   *  The flat <code>adapt</code>
   * @param fr frame which should be scored
   * @param adapt a flag enforcing an adaptation of <code>fr</code> to this model. If flag
   *        is <code>false</code> scoring code expect that <code>fr</code> is already adapted.
   * @return a new frame containing a predicted values. For classification it contains a column with
   *         prediction and distribution for all response classes. For regression it contains only
   *         one column with predicted values.
   */
  public final Frame score(Frame fr, boolean adapt) {
    long start_time = System.currentTimeMillis();
    Frame fr_hacked = new Frame(fr);
    if (isSupervised()) {
      int ridx = fr.find(_output.responseName());
      if (ridx != -1) { // drop the response for scoring!
        fr_hacked.remove(ridx);
      }
    }
    // Adapt the Frame layout - returns adapted frame and frame containing only
    // newly created vectors
    Frame[] adaptFrms = adapt ? adapt(fr_hacked,false) : null;
    // Adapted frame containing all columns - mix of original vectors from fr
    // and newly created vectors serving as adaptors
    Frame adaptFrm = adapt ? adaptFrms[0] : fr_hacked;
    // Contains only newly created vectors. The frame eases deletion of these vectors.
    Frame onlyAdaptFrm = adapt ? adaptFrms[1] : null;
    // Invoke scoring
    Frame output = scoreImpl(adaptFrm);
    // Be nice to DKV and delete vectors which i created :-)
    if (adapt) onlyAdaptFrm.delete();
    computeModelMetrics(start_time, fr, output);
    return output;
  }

  /** Score an already adapted frame.
   *
   * @param adaptFrm
   * @return A Frame containing the prediction column, and class distribution
   */
  private Frame scoreImpl(Frame adaptFrm) {
    if (isSupervised()) {
      int ridx = adaptFrm.find(_output.responseName());
      assert ridx == -1 : "Adapted frame should not contain response in scoring method!";
      assert _output.nfeatures() == adaptFrm.numCols() : "Number of model features " + _output.nfeatures() + " != number of test set columns: " + adaptFrm.numCols();
      assert adaptFrm.vecs().length == _output.nfeatures() : "Scoring data set contains wrong number of columns: " + adaptFrm.vecs().length + " instead of " + _output.nfeatures();
    }

    // Create a new vector for response
    // If the model produces a classification/enum, copy the domain into the
    // result vector.
    int nc = _output.nclasses();
    Vec [] newVecs = new Vec[]{adaptFrm.anyVec().makeZero(_output.classNames())};
    if(nc > 1)
      newVecs = ArrayUtils.join(newVecs,adaptFrm.anyVec().makeZeros(nc));
    String [] names = new String[newVecs.length];
    names[0] = "predict";
    for(int i = 1; i < names.length; ++i)
      names[i] = _output.classNames()[i-1];
    final int num_features = _output.nfeatures();
    new MRTask() {
      @Override public void map( Chunk chks[] ) {
        double tmp [] = new double[num_features];
        float preds[] = new float [_output.nclasses()==1?1:_output.nclasses()+1];
        int len = chks[0]._len;
        for( int row=0; row<len; row++ ) {
          float p[] = score0(chks,row,tmp,preds);
          for( int c=0; c<preds.length; c++ )
            chks[num_features+c].set0(row,p[c]);
        }
      }
    }.doAll(ArrayUtils.join(adaptFrm.vecs(),newVecs));

    // Return just the output columns
    return new Frame(names, newVecs);
  }

  /** Single row scoring, on a compatible Frame.  */
  public final float[] score( Frame fr, boolean exact, int row ) {
    double tmp[] = new double[fr.numCols()];
    for( int i=0; i<tmp.length; i++ )
      tmp[i] = fr.vecs()[i].at(row);
    return score(fr.names(),fr.domains(),exact,tmp);
  }

  /** Single row scoring, on a compatible set of data.  Fairly expensive to adapt. */
  public final float[] score( String names[], String domains[][], boolean exact, double row[] ) {
    return score(adapt(names,domains,exact),row,new float[_output.nclasses()]);
  }

  /** Single row scoring, on a compatible set of data, given an adaption vector */
  public final float[] score( int map[][][], double row[], float[] preds ) {
    /*FIXME final int[][] colMap = map[map.length-1]; // Response column mapping is the last array
    assert colMap.length == _output._names.length-1 : " "+Arrays.toString(colMap)+" "+Arrays.toString(_output._names);
    double tmp[] = new double[colMap.length]; // The adapted data
    for( int i=0; i<colMap.length; i++ ) {
      // Column mapping, or NaN for missing columns
      double d = colMap[i]==-1 ? Double.NaN : row[colMap[i]];
      if( map[i] != null ) {    // Enum mapping
        int e = (int)d;
        if( e < 0 || e >= map[i].length ) d = Double.NaN; // User data is out of adapt range
        else {
          e = map[i][e];
          d = e==-1 ? Double.NaN : (double)e;
        }
      }
      tmp[i] = d;
    }
    return score0(tmp,preds);   // The results. */
    return null;
  }

  /** Build an adaption array.  The length is equal to the Model's vector length.
   *  Each inner 2D-array is a
   *  compressed domain map from data domains to model domains - or null for non-enum
   *  columns, or null for identity mappings.  The extra final int[] is the
   *  column mapping itself, mapping from model columns to data columns. or -1
   *  if missing.
   *  If 'exact' is true, will throw if there are:
   *    any columns in the model but not in the input set;
   *    any enums in the data that the model does not understand
   *    any enums returned by the model that the data does not have a mapping for.
   *  If 'exact' is false, these situations will use or return NA's instead.
   */
  protected int[][][] adapt( String names[], String domains[][], boolean exact) {
    int maplen = names.length;
    int map[][][] = new int[maplen][][];
    // Make sure all are compatible
    for( int c=0; c<names.length;++c) {
            // Now do domain mapping
      String ms[] = _output._domains[c];  // Model enum
      String ds[] =  domains[c];  // Data  enum
      if( ms == ds ) { // Domains trivially equal?
      } else if( ms == null ) {
        throw new IllegalArgumentException("Incompatible column: '" + _output._names[c] + "', expected (trained on) numeric, was passed a categorical");
      } else if( ds == null ) {
        if( exact )
          throw new IllegalArgumentException("Incompatible column: '" + _output._names[c] + "', expected (trained on) categorical, was passed a numeric");
        throw H2O.unimpl();     // Attempt an asEnum?
      } else if( !Arrays.deepEquals(ms, ds) ) {
        map[c] = getDomainMapping(_output._names[c], ms, ds, exact);
      } // null mapping is equal to identity mapping
    }
    return map;
  }

  protected ModelMetrics computeModelMetrics(long start_time, Frame frame, Frame predictions) {
    // Always calculate error, regardless of whether this is being called by the REST API or the Java API.
    // TODO: SupervisedModel.calcError can't handle multinomial classification yet.  Should be able to create CMs.
    ModelMetrics mm=null;
    if (_output.getModelCategory() == Model.ModelCategory.Binomial /* || _output.getModelCategory() == Model.ModelCategory.Multinomial */) {
      SupervisedModel sm = (SupervisedModel)this;
      AUC auc = new AUC();
      ConfusionMatrix cm = new ConfusionMatrix();
      HitRatio hr = new HitRatio();
      sm.calcError(frame, frame.vec(_output.responseName()), predictions, predictions, "Prediction error:",
              true, 20, cm, auc, hr);
      mm =  ModelMetrics.createModelMetrics(this, frame, System.currentTimeMillis() - start_time, start_time, auc.aucdata, cm);
    } else if (_output.getModelCategory() == Model.ModelCategory.Regression) {
      SupervisedModel sm = (SupervisedModel) this;
      sm.calcError(frame, frame.vec(_output.responseName()), predictions, predictions, "Prediction error:",
              true, 20, null, null, null);
      mm = ModelMetrics.createModelMetrics(this, frame, System.currentTimeMillis() - start_time, start_time, null, null);
    }
    if( mm != null ) _output.addModelMetrics(mm);
    return mm;
  }

  /**
   * Type of missing columns during adaptation between train/test datasets
   * Overload this method for models that have sparse data handling.
   * Otherwise, NaN is used.
   * @return real-valued number (can be NaN)
   */
  protected double missingColumnsType() { return Double.NaN; }

  /** Build an adapted Frame from the given Frame. Useful for efficient bulk
   *  scoring of a new dataset to an existing model.  Same adaption as above,
   *  but expressed as a Frame instead of as an int[][]. The returned Frame
   *  does not have a response column.
   *  It returns a <b>two element array</b> containing an adapted frame and a
   *  frame which contains only vectors which where adapted (the purpose of the
   *  second frame is to delete all adapted vectors with deletion of the
   *  frame). */
  public Frame[] adapt( final Frame fr, boolean exact) {
    return adapt(fr, exact, true);
  }

  public Frame[] adapt( final Frame fr, boolean exact, boolean haveResponse) {
    Frame vfr = new Frame(fr); // To avoid modification of original frame fr
    int n = _output._names.length;
    if (haveResponse && isSupervised()) {
      int ridx = vfr.find(_output._names[n - 1]);
      if (ridx != -1 && ridx != vfr._names.length - 1) { // Unify frame - put response to the end
        String name = vfr._names[ridx];
        vfr.add(name, vfr.remove(ridx));
      }
      n = ridx == -1 ? _output._names.length - 1 : _output._names.length;
    }
    String [] names = isSupervised() ? Arrays.copyOf(_output._names, n) : _output._names.clone();
    Frame  [] subVfr;
    // replace missing columns with NaNs (or 0s for DeepLearning with sparse data)
    subVfr = vfr.subframe(names, missingColumnsType());
    vfr = subVfr[0]; // extract only subframe but keep the rest for delete later
    Vec[] frvecs = vfr.vecs();
    boolean[] toEnum = new boolean[frvecs.length];
    if(!exact) for(int i = 0; i < n;++i)
      if(_output._domains[i] != null && !frvecs[i].isEnum()) {// if model expects domain but input frame does not have domain => switch vector to enum
        frvecs[i] = frvecs[i].toEnum();
        toEnum[i] = true;
      }
    int[][][] map = adapt(names,vfr.domains(),exact);
    assert map.length == names.length; // Be sure that adapt call above do not skip any column
    ArrayList<Vec> avecs = new ArrayList<>(); // adapted vectors
    ArrayList<String> anames = new ArrayList<>(); // names for adapted vector

    for( int c=0; c<map.length; c++ ) // Iterate over columns
      if(map[c] != null) { // Column needs adaptation
        Vec adaptedVec;
        if (toEnum[c]) { // Vector was flipped to column already, compose transformation
          adaptedVec = TransfVec.compose( (TransfVec) frvecs[c], map[c], vfr.domains()[c], false);
        } else adaptedVec = frvecs[c].makeTransf(map[c], vfr.domains()[c]);
        avecs.add(frvecs[c] = adaptedVec);
        anames.add(names[c]); // Collect right names
      } else if (toEnum[c]) { // Vector was transformed to enum domain, but does not need adaptation we need to record it
        avecs.add(frvecs[c]);
        anames.add(names[c]);
      }
    // Fill trash bin by vectors which need to be deleted later by the caller.
    Frame vecTrash = new Frame(anames.toArray(new String[anames.size()]), avecs.toArray(new Vec[avecs.size()]));
    if (subVfr[1]!=null) vecTrash.add(subVfr[1]);
    return new Frame[] { new Frame(names,frvecs), vecTrash };
  }

  /** Returns a mapping between values of model domains (<code>modelDom</code>) and given column domain.
   *  @see #getDomainMapping(String, String[], String[], boolean) */
  public static int[][] getDomainMapping(String[] modelDom, String[] colDom, boolean exact) {
    return getDomainMapping(null, modelDom, colDom, exact);
  }

  /**
   * Returns a mapping for given column according to given <code>modelDom</code>.
   * In this case, <code>modelDom</code> is
   *
   * @param colName name of column which is mapped, can be null.
   * @param modelDom
   * @param logNonExactMapping
   * @return A mapping for given column according to given <code>modelDom</code>.
   */
  public static int[][] getDomainMapping(String colName, String[] modelDom, String[] colDom, boolean logNonExactMapping) {
    int emap[] = new int[modelDom.length];
    boolean bmap[] = new boolean[modelDom.length];
    HashMap<String,Integer> md = new HashMap<>((int) ((colDom.length/0.75f)+1));
    for( int i = 0; i < colDom.length; i++) md.put(colDom[i], i);
    for( int i = 0; i < modelDom.length; i++) {
      Integer I = md.get(modelDom[i]);
      if (I == null && logNonExactMapping)
        Log.warn("Domain mapping: target domain contains the factor '"+modelDom[i]+"' which DOES NOT appear in input domain " + (colName!=null?"(column: " + colName+")":""));
      if (I!=null) {
        emap[i] = I;
        bmap[i] = true;
      }
    }
    if (logNonExactMapping) { // Inform about additional values in column domain which do not appear in model domain
      for (int i=0; i<colDom.length; i++) {
        boolean found = false;
        for (int anEmap : emap)
          if (anEmap == i) {
            found = true;
            break;
          }
        if (!found)
          Log.warn("Domain mapping: target domain DOES NOT contain the factor '"+colDom[i]+"' which appears in input domain "+ (colName!=null?"(column: " + colName+")":""));
      }
    }

    // produce packed values
    int[][] res = water.fvec.TransfVec.pack(emap, bmap);
    // Sort values in numeric order to support binary search in TransfVec
    water.fvec.TransfVec.sortWith(res[0], res[1]);
    return res;
  }

  /** Bulk scoring API for one row.  Chunks are all compatible with the model,
   *  and expect the last Chunks are for the final distribution and prediction.
   *  Default method is to just load the data into the tmp array, then call
   *  subclass scoring logic. */
  protected float[] score0( Chunk chks[], int row_in_chunk, double[] tmp, float[] preds ) {
    assert chks.length>=_output._names.length;
    for( int i=0; i<_output._names.length; i++ )
      tmp[i] = chks[i].at0(row_in_chunk);
    return score0(tmp,preds);
  }

  /** Subclasses implement the scoring logic.  The data is pre-loaded into a
   *  re-used temp array, in the order the model expects.  The predictions are
   *  loaded into the re-used temp array, which is also returned.  */
  protected abstract float[] score0(double data[/*ncols*/], float preds[/*nclasses+1*/]);
  // Version where the user has just ponied-up an array of data to be scored.
  // Data must be in proper order.  Handy for JUnit tests.
  public double score(double [] data){ return ArrayUtils.maxIndex(score0(data, new float[_output.nclasses()]));  }

  @Override protected Futures remove_impl( Futures fs ) {
    for( Key k : _output._model_metrics )
      k.remove(fs);
    return fs;
  }

  @Override public long checksum() {
    return _parms.checksum() *
            _output.checksum();
  }
}
