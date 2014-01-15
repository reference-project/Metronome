package tv.floe.metronome.deeplearning.neuralnetwork.optimize;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.mahout.math.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cc.mallet.optimize.InvalidOptimizableException;
import cc.mallet.optimize.Optimizable;
import cc.mallet.optimize.OptimizationException;
import cc.mallet.optimize.Optimizer;

import tv.floe.metronome.deeplearning.neuralnetwork.core.BaseNeuralNetworkVectorized;
import tv.floe.metronome.math.MatrixUtils;


public abstract class NeuralNetworkOptimizer implements Optimizable.ByGradientValue,Serializable {

	public NeuralNetworkOptimizer(BaseNeuralNetworkVectorized network, double lr,Object[] trainingParams) {
		
		this.network = network;
		this.lr = lr;
		this.extraParams = trainingParams;
		
	}


	private static final long serialVersionUID = 4455143696487934647L;
	protected BaseNeuralNetworkVectorized network;
	protected double lr;
	protected Object[] extraParams;
	protected double tolerance = 0.000001;
	protected static Logger log = LoggerFactory.getLogger(NeuralNetworkOptimizer.class);
	protected List<Double> errors = new ArrayList<Double>();
	protected double minLearningRate = 0.001;
	protected transient Optimizer opt;
	
	
	
	public void train(Matrix x) {
		
		if (opt == null) {
			opt = new cc.mallet.optimize.LimitedMemoryBFGS(this);
		}

		boolean done = false;
		network.train(x, lr, extraParams);
		
		while (!done) {
			
			try {
				done = opt.optimize();
			}
			catch(InvalidOptimizableException e) {
				done = true;
				log.info("Error on step; finishing");
			}
			catch(OptimizationException e2) {
				done = true;
				log.info("Error on step; finishing");

			}

		}
	}


	public List<Double> getErrors() {
		return errors;
	}


	@Override
	public int getNumParameters() {
		//return network.connectionWeights.length + network.hBias.length + network.vBias.length;
		return MatrixUtils.length(network.connectionWeights ) + MatrixUtils.length( network.hiddenBiasNeurons ) + MatrixUtils.length( network.visibleBiasNeurons );
	}


	@Override
	public void getParameters(double[] buffer) {
		/*
		 * If we think of the parameters of the model (W,vB,hB)
		 * as a solid line for the optimizer, we get the following:
		 * 
		 */

		int idx = 0;
		
		for (int i = 0; i < MatrixUtils.length( network.connectionWeights ); i++) {
		
			buffer[ idx++ ] = MatrixUtils.getElement( network.connectionWeights, i );
			
		}
		
		for (int i = 0; i < MatrixUtils.length( network.visibleBiasNeurons ); i++) {
			
			buffer[ idx++ ] = MatrixUtils.getElement( network.visibleBiasNeurons, i );
			
		}
				
		for (int i = 0; i < MatrixUtils.length( network.hiddenBiasNeurons ); i++) {
			
			buffer[ idx++ ] = MatrixUtils.getElement( network.hiddenBiasNeurons, i );
			
		}
		
		
	}


	@Override
	public double getParameter(int index) {
		
		// beyond weight matrix
		if (index >= MatrixUtils.length( network.connectionWeights ) ) {
			
			
			// beyond visible bias
			if (index >= MatrixUtils.length( network.visibleBiasNeurons ) ) {
				
				return MatrixUtils.getElement( network.hiddenBiasNeurons, index );
				
			} else {
				
				return MatrixUtils.getElement( network.visibleBiasNeurons, index );
				
			}
			
			
		}
		
		return MatrixUtils.getElement( network.connectionWeights, index );

	}


	/**
	 * 
	 * If we think of the parameters of the model (W,vB,hB)
	 * as a solid line for the optimizer, we get the following:
	 * 
	 */
	@Override
	public void setParameters(double[] params) {

		int idx = 0;
		
		
		for (int i = 0; i < MatrixUtils.length( network.connectionWeights ); i++ ) {
			
			MatrixUtils.setElement( network.connectionWeights, i, params[ idx++ ] );
			
		}
		
		
		for (int i = 0; i < MatrixUtils.length( network.visibleBiasNeurons ); i++ ) {
			
			MatrixUtils.setElement( network.visibleBiasNeurons, i, params[ idx++ ] );
			
		}
		
		
		for (int i = 0; i < MatrixUtils.length( network.hiddenBiasNeurons ); i++ ) {
			
			MatrixUtils.setElement( network.hiddenBiasNeurons, i, params[ idx++ ] );
			
		}
		
		
	}


	@Override
	public void setParameter(int index, double value) {
/*		//beyond weight matrix
		if(index >= network.W.length) {
			//beyond visible bias
			if(index >= network.vBias.length) {
				int i = index - network.hBias.length;
				network.hBias.put(i, value);
			}
			else {
				int i = index - network.vBias.length;
				network.vBias.put(i,value);

			}

		}
		network.W.put(index, value);
		*/
		
		// beyond weight matrix
		if (index >= MatrixUtils.length( network.connectionWeights ) ) {
			
			
			// beyond visible bias
			if (index >= MatrixUtils.length( network.visibleBiasNeurons ) ) {
				
				int i = index - MatrixUtils.length( network.hiddenBiasNeurons );
				MatrixUtils.setElement( network.hiddenBiasNeurons, index, value );
				
			} else {
				
				int i = index - MatrixUtils.length( network.visibleBiasNeurons );
				MatrixUtils.setElement( network.visibleBiasNeurons, index, value );
				
			}
			
			
		}
		
		MatrixUtils.setElement( network.connectionWeights, index, value );
		
		
	}


	@Override
	public abstract void getValueGradient(double[] buffer);


	@Override
	public double getValue() {
		return network.lossFunction(extraParams);
	}



}