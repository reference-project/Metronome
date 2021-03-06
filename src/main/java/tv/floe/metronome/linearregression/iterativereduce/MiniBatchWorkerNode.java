package tv.floe.metronome.linearregression.iterativereduce;


import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.classifier.sgd.UniformPrior;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.RandomAccessSparseVector;
import org.apache.mahout.math.Vector;

import tv.floe.metronome.io.records.RCV1RecordFactory;
import tv.floe.metronome.io.records.RecordFactory;
import tv.floe.metronome.linearregression.ModelParameters;
import tv.floe.metronome.linearregression.ParallelOnlineLinearRegression;
import tv.floe.metronome.linearregression.ParameterVector;
import tv.floe.metronome.linearregression.SquaredErrorLossFunction;
import tv.floe.metronome.metrics.Metrics;
import tv.floe.metronome.utils.Utils;

import com.cloudera.iterativereduce.ComputableWorker;
import com.cloudera.iterativereduce.io.RecordParser;
import com.cloudera.iterativereduce.io.TextRecordParser;
import com.cloudera.iterativereduce.yarn.appworker.ApplicationWorker;
/*
 import com.cloudera.knittingboar.messages.iterativereduce.ParameterVectorGradient;
 import com.cloudera.knittingboar.messages.iterativereduce.ParameterVectorGradientUpdatable;
 import com.cloudera.knittingboar.metrics.POLRMetrics;
 import com.cloudera.knittingboar.records.CSVBasedDatasetRecordFactory;
 import com.cloudera.knittingboar.records.RCV1RecordFactory;
 import com.cloudera.knittingboar.records.RecordFactory;
 import com.cloudera.knittingboar.records.TwentyNewsgroupsRecordFactory;
 */

import com.google.common.collect.Lists;

/**
 * Experimental Mini-Batch worker node
 * 
 * @author josh
 *
 */
public class MiniBatchWorkerNode extends NodeBase implements
		ComputableWorker<ParameterVectorUpdateable> {

	private static final Log LOG = LogFactory.getLog(MiniBatchWorkerNode.class);

	int masterTotal = 0;

	public ParallelOnlineLinearRegression polr = null; // lmp.createRegression();
	public ModelParameters polr_modelparams;

	public String internalID = "0";
	private RecordFactory VectorFactory = null;
	
	private SquaredErrorLossFunction loss_function = new SquaredErrorLossFunction();

	private TextRecordParser lineParser = null;

	private boolean IterationComplete = false;
	private int CurrentIteration = 0;

	// basic stats tracking
	Metrics metrics = new Metrics();

	double averageLineCount = 0.0;
	int k = 0;
	double step = 0.0;
	int[] bumps = new int[] { 1, 2, 5 };
	double lineCount = 0;

	/**
	 * Sends a full copy of the multinomial logistic regression array of
	 * parameter vectors to the master - this method plugs the local parameter
	 * vector into the message
	 */
	public ParameterVector GenerateUpdate() {

		ParameterVector vector = new ParameterVector();
		vector.parameter_vector = this.polr.getBeta().clone(); // this.polr.getGamma().getMatrix().clone();

		if (this.lineParser.hasMoreRecords()) {
			vector.IterationComplete = 0;
		} else {
			vector.IterationComplete = 1;
		}

		vector.CurrentIteration = this.CurrentIteration;

		vector.AvgError = (new Double(metrics.AvgError * 100))
				.floatValue();
		vector.TrainedRecords = (new Long(metrics.TotalRecordsProcessed))
				.intValue();

		return vector;

	}

	/**
	 * The IR::Compute method - this is where we do the next batch of records
	 * for SGD
	 * 
	 */
	@Override
	public ParameterVectorUpdateable compute() {

		
		Text value = new Text();
		long batch_vec_factory_time = 0;

		double err_buf = 0;
		
		boolean result = true;
		int batch_count = 0;
		int batch_limit = 5;
		Vector miniBatchBuffer = new RandomAccessSparseVector(this.FeatureVectorSize);

		int run_count = 0;
		
		while (this.lineParser.hasMoreRecords()) {

			try {
				result = this.lineParser.next(value);
			} catch (IOException e1) {
				e1.printStackTrace();
			}

			if (result) {

				long startTime = System.currentTimeMillis();

				Vector v = new RandomAccessSparseVector(this.FeatureVectorSize);
				double actual = 0.0;
				try {

					actual = this.VectorFactory
							.processLineAlt(value.toString(), v);
				} catch (Exception e) {
					e.printStackTrace();
				}

				long endTime = System.currentTimeMillis();

				batch_vec_factory_time += (endTime - startTime);

				// calc stats ---------

				double mu = Math.min(k + 1, 200);
				
				
				// the dot product of the parameter vector and the current instance
				// is the hypothesis value for the currnet instance
				double hypothesis_value = v.dot(this.polr.getBeta().viewRow(0));
				
				double error = Math.abs( hypothesis_value - actual );


				if (Double.POSITIVE_INFINITY == error) { 
					
				} else {
								err_buf += error;
				}
				
				// ####### where we train ############
				// update the parameter vector with the actual value and the instance data

				this.polr.trainMiniBatch(actual, v, miniBatchBuffer);
				
				batch_count++;
				
				if ( batch_count >= batch_limit ) {
					
					// 1. update all of the coefficients with the average update
					
					this.polr.miniBatchUpdateParameterVector(batch_limit, miniBatchBuffer);
					
					
					// reset batch buffer --- best way to do this?
					miniBatchBuffer = miniBatchBuffer.like();
					
					batch_count = 0;
				}
				
				run_count++;
				k++;
				metrics.TotalRecordsProcessed = k;

				this.polr.close();

			} else {

				// nothing else to process in split!

			} // if

		} // while
		
		err_buf = err_buf / run_count;
		metrics.AvgError = err_buf;
		return new ParameterVectorUpdateable(this.GenerateUpdate());
	}

	public ParameterVectorUpdateable getResults() {
		return new ParameterVectorUpdateable(GenerateUpdate());
	}

	/**
	 * This is called when we recieve an update from the master
	 * 
	 * here we - replace the gradient vector with the new global gradient vector
	 * 
	 */
	@Override
	public void update(ParameterVectorUpdateable t) {
		ParameterVector global_update = t.get();
		
		// set the local parameter vector to the global aggregate ("beta")
		this.polr.SetBeta(global_update.parameter_vector);

	}

	@Override
	public void setup(Configuration c) {

		this.conf = c;

		try {


			this.FeatureVectorSize = LoadIntConfVarOrException(
					"com.cloudera.knittingboar.setup.FeatureVectorSize",
					"Error loading config: could not load feature vector size");

			this.NumberIterations = this.conf.getInt("app.iteration.count", 1);

			// protected double Lambda = 1.0e-4;
			this.Lambda = Double.parseDouble(this.conf.get(
					"com.cloudera.knittingboar.setup.Lambda", "1.0e-4"));

			// protected double LearningRate = 50;
			this.LearningRate = Double.parseDouble(this.conf.get(
					"com.cloudera.knittingboar.setup.LearningRate", "10"));

			// maps to either CSV, 20newsgroups, or RCV1
			this.RecordFactoryClassname = LoadStringConfVarOrException(
					"com.cloudera.knittingboar.setup.RecordFactoryClassname",
					"Error loading config: could not load RecordFactory classname");

			if (this.RecordFactoryClassname
					.equals(RecordFactory.CSV_RECORDFACTORY)) {

				// so load the CSV specific stuff ----------

				// predictor label names
				this.PredictorLabelNames = LoadStringConfVarOrException(
						"com.cloudera.knittingboar.setup.PredictorLabelNames",
						"Error loading config: could not load predictor label names");

				// predictor var types
				this.PredictorVariableTypes = LoadStringConfVarOrException(
						"com.cloudera.knittingboar.setup.PredictorVariableTypes",
						"Error loading config: could not load predictor variable types");

				// target variables
				this.TargetVariableName = LoadStringConfVarOrException(
						"com.cloudera.knittingboar.setup.TargetVariableName",
						"Error loading config: Target Variable Name");

				// column header names
				this.ColumnHeaderNames = LoadStringConfVarOrException(
						"com.cloudera.knittingboar.setup.ColumnHeaderNames",
						"Error loading config: Column Header Names");


			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		this.SetupPOLR();
	}

	private void SetupPOLR() {

		// do splitting strings into arrays here...
		String[] predictor_label_names = this.PredictorLabelNames.split(",");
		String[] variable_types = this.PredictorVariableTypes.split(",");

		polr_modelparams = new ModelParameters();
		polr_modelparams.setTargetVariable(this.TargetVariableName);
		polr_modelparams.setNumFeatures(this.FeatureVectorSize);
		polr_modelparams.setUseBias(true);

		List<String> typeList = Lists.newArrayList();
		for (int x = 0; x < variable_types.length; x++) {
			typeList.add(variable_types[x]);
		}

		List<String> predictorList = Lists.newArrayList();
		for (int x = 0; x < predictor_label_names.length; x++) {
			predictorList.add(predictor_label_names[x]);
		}

		// where do these come from?
		polr_modelparams.setTypeMap(predictorList, typeList);
		polr_modelparams.setLambda(this.Lambda); // based on defaults - match
													// command line
		polr_modelparams.setLearningRate(this.LearningRate); // based on
																// defaults -
																// match command
																// line

		// setup record factory stuff here ---------

		if (RecordFactory.TWENTYNEWSGROUPS_RECORDFACTORY
				.equals(this.RecordFactoryClassname)) {

		} else if (RecordFactory.RCV1_RECORDFACTORY
				.equals(this.RecordFactoryClassname)) {

			this.VectorFactory = new RCV1RecordFactory();

		} else {

			// it defaults to the CSV record factor, but a custom one
/*
			this.VectorFactory = new CSVBasedDatasetRecordFactory(
					this.TargetVariableName, polr_modelparams.getTypeMap());

			((CSVBasedDatasetRecordFactory) this.VectorFactory)
					.firstLine(this.ColumnHeaderNames);
*/
		}

		polr_modelparams.setTargetCategories(this.VectorFactory
				.getTargetCategories());

		// ----- this normally is generated from the POLRModelParams ------

		this.polr = new ParallelOnlineLinearRegression(
				this.FeatureVectorSize, new UniformPrior()).alpha(1)
				.stepOffset(1000).decayExponent(0.9).lambda(3.0e-5)
				.learningRate(this.LearningRate);

		polr_modelparams.setPOLR(polr);

	}

	@Override
	public void setRecordParser(RecordParser r) {
		this.lineParser = (TextRecordParser) r;
	}

	/**
	 * only implemented for completeness with the interface, we argued over how
	 * to implement this. - this is currently a legacy artifact
	 */
	@Override
	public ParameterVectorUpdateable compute(
			List<ParameterVectorUpdateable> records) {
		// TODO Auto-generated method stub
		return compute();
	}

	public static void main(String[] args) throws Exception {
		TextRecordParser parser = new TextRecordParser();
		MiniBatchWorkerNode pwn = new MiniBatchWorkerNode();
		ApplicationWorker<ParameterVectorUpdateable> aw = new ApplicationWorker<ParameterVectorUpdateable>(
				parser, pwn, ParameterVectorUpdateable.class);

		ToolRunner.run(aw, args);
	}

	/**
	 * returns false if we're done with iterating over the data
	 * 
	 * @return
	 */
	@Override
	public boolean IncrementIteration() {

		this.CurrentIteration++;
		this.IterationComplete = false;
		this.lineParser.reset();


		if (this.CurrentIteration >= this.NumberIterations) {
			System.out.println("POLRWorkerNode: [ done with all iterations ]");
			return false;
		}

		return true;

	}

}
