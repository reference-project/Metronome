package tv.floe.metronome.irunit;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;

import tv.floe.metronome.utils.Utils;

import antlr.ByteBuffer;

import com.cloudera.iterativereduce.ComputableMaster;
import com.cloudera.iterativereduce.ComputableWorker;
//import com.cloudera.knittingboar.messages.iterativereduce.ParameterVectorGradientUpdatable;
import com.cloudera.iterativereduce.Updateable;
import com.cloudera.iterativereduce.io.TextRecordParser;

/**
 * Modified version of the IterativeReduce IRDriver
 * 
 * @author josh
 *
 * @param <T>
 */
public class IRUnitDriver<T> {

	private static JobConf defaultConf = new JobConf();
	private static FileSystem localFs = null;
	static {
		try {
			defaultConf.set("fs.defaultFS", "file:///");
			localFs = FileSystem.getLocal(defaultConf);
		} catch (IOException e) {
			throw new RuntimeException("init failure", e);
		}
	}

	private static Path workDir = new Path("/tmp/");

	Properties props;

	private ComputableMaster master;
	private ArrayList<ComputableWorker> workers;
	private String app_properties_file = "";
	ArrayList<Updateable> worker_results = new ArrayList<Updateable>();
	Updateable master_result = null;
	boolean bContinuePass = true;

	InputSplit[] splits;

	/**
	 * need to load the app.properties file
	 * 
	 * @return
	 */
	public Configuration generateDebugConfigurationObject() {

		Configuration c = new Configuration();

/*		String[] props_to_copy = {
				"app.iteration.count",
				"com.cloudera.knittingboar.setup.FeatureVectorSize",
				"com.cloudera.knittingboar.setup.RecordFactoryClassname",
				"com.cloudera.knittingboar.setup.LearningRate"
		};
	*/	
		 for(Entry<Object, Object> e : props.entrySet()) {
	           // System.out.println(e.getKey());
			 c.set(e.getKey().toString(), e.getValue().toString());
	        }		
/*
		for ( int x = 0; x < props_to_copy.length; x++ ) {
			if (null != this.props.getProperty(props_to_copy[x])) {
				c.set(props_to_copy[x], this.props.getProperty(props_to_copy[x]));
			} else {
			//	System.out.println("> Conf: Did not find in properties file - " + props_to_copy[x]);
			}
		}
*/
		
		return c;

	}

	/**
	 * generate splits for this run
	 * 
	 * @param input_path
	 * @param job
	 * @return
	 */
	private InputSplit[] generateDebugSplits(Path input_path, JobConf job) {

		long block_size = localFs.getDefaultBlockSize();

		System.out.println("default block size: " + (block_size / 1024 / 1024)
				+ "MB");

		// ---- set where we'll read the input files from -------------
		FileInputFormat.setInputPaths(job, input_path);

		// try splitting the file in a variety of sizes
		TextInputFormat format = new TextInputFormat();
		format.configure(job);

		int numSplits = 1;

		InputSplit[] splits = null;

		try {
			splits = format.getSplits(job, numSplits);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return splits;

	}

	public IRUnitDriver(String app_prop) {

		this.app_properties_file = app_prop;
		
	}

	/**
	 * Setup components of the IR app run 1. load app.properties 2. msg arrays
	 * 3. calc local splits 4. setup master 5. setup workers based on number of
	 * splits
	 * 
	 */
	public void Setup() {

		// ----- load the app.properties file

		// String configFile = (args.length < 1) ?
		// ConfigFields.DEFAULT_CONFIG_FILE : args[0];
		this.props = new Properties();
		// Configuration conf = getConf();

		try {
			FileInputStream fis = new FileInputStream(this.app_properties_file);
			props.load(fis);
			fis.close();
		} catch (FileNotFoundException ex) {
			// throw ex; // TODO: be nice
			System.out.println(ex);
		} catch (IOException ex) {
			// throw ex; // TODO: be nice
			System.out.println(ex);
		}

		// setup msg arrays

		// calc splits

		// ---- this all needs to be done in
		JobConf job = new JobConf(defaultConf);
		
		// app.input.path
		
		Path splitPath = new Path( props.getProperty("app.input.path") );

		System.out.println( "app.input.path = " + splitPath );
		
		// TODO: work on this, splits are generating for everything in dir
		InputSplit[] splits = generateDebugSplits(splitPath, job);

		System.out.println("split count: " + splits.length);

		try {
			// this.master = (ComputableMaster)
			// custom_master_class.newInstance();

			Class<?> master_clazz = Class.forName(props
					.getProperty("yarn.master.main"));
			Constructor<?> master_ctor = master_clazz
					.getConstructor();
			this.master = (ComputableMaster) master_ctor.newInstance(); // new
																		// Object[]
																		// {
																		// ctorArgument
																		// });
			System.out.println("Using master class: " + props
					.getProperty("yarn.master.main"));

		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		this.master.setup(this.generateDebugConfigurationObject());

		this.workers = new ArrayList<ComputableWorker>();

		System.out.println("Using worker class: " + props
				.getProperty("yarn.worker.main"));
		
		for (int x = 0; x < splits.length; x++) {
			
			System.out.println( "IRUnit > Split > " + splits[x].toString() );

			ComputableWorker worker = null;
			Class<?> worker_clazz;
			try {
				worker_clazz = Class.forName(props
						.getProperty("yarn.worker.main"));
				Constructor<?> worker_ctor = worker_clazz
						.getConstructor();
				worker = (ComputableWorker) worker_ctor.newInstance(); // new
																		// Object[]
																		// {
																		// ctorArgument
																		// });

			} catch (ClassNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (SecurityException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (NoSuchMethodException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InstantiationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			// simulates the conf stuff
			worker.setup(this.generateDebugConfigurationObject());

			// InputRecordsSplit custom_reader_0 = new InputRecordsSplit(job,
			// splits[x]);
			TextRecordParser txt_reader = new TextRecordParser();

			long len = Integer.parseInt(splits[x].toString().split(":")[2]
					.split("\\+")[1]);

			txt_reader.setFile(splits[x].toString().split(":")[1], 0, len);

			worker.setRecordParser(txt_reader);

			workers.add(worker);

			System.out.println("> Setup Worker " + x);
		} // for

	}

	public void SimulateRun() {

		ArrayList<Updateable> master_results = new ArrayList<Updateable>();
		ArrayList<Updateable> worker_results = new ArrayList<Updateable>();

		long ts_start = System.currentTimeMillis();

		//System.out.println("start-ms:" + ts_start);

		int iterations = Integer.parseInt(props
				.getProperty("app.iteration.count"));

		System.out.println("Starting Epochs (" + iterations + ")...");
		
		for (int x = 0; x < iterations; x++) {

			for (int worker_id = 0; worker_id < workers.size(); worker_id++) {

				Updateable result = workers.get(worker_id).compute();
				java.nio.ByteBuffer bb = result.toBytes();
				result.fromBytes(bb);
				
				worker_results.add(result);
				// ParameterVectorGradient msg0 =
				// workers.get(worker_id).GenerateUpdate();

			} // for

			Updateable master_result = this.master.compute(worker_results,
					master_results);
			

			// process global updates
			for (int worker_id = 0; worker_id < workers.size(); worker_id++) {

				workers.get(worker_id).update(master_result);
				workers.get(worker_id).IncrementIteration();

			}
/*			
		      if (master_result.get().IterationComplete == 1) {
		          
		          System.out.println( " -------- end of pass ------- " );

		        // simulates framework checking this and iterating
		          for ( int worker_id = 0; worker_id < workers.size(); worker_id++ ) {
		            
		            bContinuePass = workers.get(worker_id).IncrementIteration();

		          } // for
			*/

		} // for
		
		System.out.println("Complete " + iterations + " Iterations Per Worker.");

		//String output_path = this.props.getProperty("app.output.path");
		
		//System.out.println("Writing the output to: " + output_path);
		
		
		// make sure we have somewhere to write the model
		if (null != this.props.getProperty("app.output.path")) {
			
			String output_path = this.props.getProperty("app.output.path");
			
			System.out.println("Writing the output to: " + output_path);
			

			try {

				Path out = new Path(output_path); 
				FileSystem fs =
						  out.getFileSystem(defaultConf); 
				
				FSDataOutputStream fos;

				fos = fs.create(out);
				  //LOG.info("Writing master results to " + out.toString());
				  master.complete(fos);
				  
				  fos.flush(); 
				  fos.close();


				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			  

		} else {
			
			System.out.println("Not Firing Master::Complete() function due to no output path in conf");
			
		}


	}
	
	public ComputableMaster getMaster() {
		
		return this.master;
		
	}
	
	public ArrayList<ComputableWorker> getWorker() {
		
		return this.workers;
		
	}

}
