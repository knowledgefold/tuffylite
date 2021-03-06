package tuffy.infer;


import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.postgresql.PGConnection;

import tuffy.db.RDB;
import tuffy.ground.partition.Component;
import tuffy.ground.partition.Partition;
import tuffy.infer.ds.GAtom;
import tuffy.infer.ds.GClause;
import tuffy.mln.MarkovLogicNetwork;
import tuffy.mln.Predicate;
import tuffy.mln.Type;
import tuffy.util.BitSetDoublePair;
import tuffy.util.BitSetIntPair;
import tuffy.util.Config;
import tuffy.util.ExceptionMan;
import tuffy.util.FileMan;
import tuffy.util.SeededRandom;
import tuffy.util.StringMan;
import tuffy.util.Timer;
import tuffy.util.UIMan;
import tuffy.util.myInt;

/**
 * Methods for moving data around between the RDBMS and memory,
 * as well as writing inference results to files.
 * TODO(feng) change data dump routines for raw string constants
 */
public class DataMover {
	public RDB db;
	MarkovLogicNetwork mln;

	// for mle
	public ArrayList<String> topKTables = new ArrayList<String>();
	public ArrayList<Double> topKFreq = new ArrayList<Double>();
	public ArrayList<Double> realCostCache = new ArrayList<Double>();

	public DataMover(MarkovLogicNetwork mln){
		this.mln = mln;
		this.db = mln.getRDB();
	}

	/**
	 * Load the truth table of atoms from the database.
	 */
	public LinkedHashMap<Integer, Boolean> loadTruthTable(String relAtoms){
		LinkedHashMap<Integer, Boolean> map = new LinkedHashMap<Integer, Boolean>();
		String sql = "SELECT atomID, truth FROM " + relAtoms;
		ResultSet rs = db.query(sql);
		try {
			while(rs.next()){
				int aid = rs.getInt(1);
				boolean truth = rs.getBoolean(2);
				map.put(aid, truth);
			}
			rs.close();
		} catch (SQLException e) {
			ExceptionMan.handle(e);
		}
		return map;
	}

	public MRF loadMrfFromDb(String relAtoms, String relClauses){
		MRF mrf = new MRF(mln);
		loadMrfFromDb(mrf, relAtoms, relClauses);
		return mrf;
	}


	/**
	 * Load the entire grounding result into memory as an MRF.
	 * Also build the atom-clause index.
	 */
	public MRF loadMrfFromDb(MRF mrf, String relAtoms, String relClauses){
		mrf.ownsAllAtoms = true;
		String sql = "SELECT atomID, truth, isquery, isqueryevid FROM " + relAtoms;
		try (ResultSet rs = db.query(sql)){
			//db.disableAutoCommitForNow();
			// load atoms
			UIMan.verbose(3, "Selecting atoms from DB...");
			while(rs.next()){
				if (Timer.hasTimedOut()) {
					ExceptionMan.die("Tuffy timed out reading atoms from DB");
				}
				GAtom n = new GAtom(rs.getInt("atomID"));
				n.truth = rs.getBoolean("truth");

				n.isquery = rs.getBoolean("isquery");

				n.isquery_evid = rs.getBoolean("isqueryevid");



				/*
				if(rs.getInt("keyID") != -1){
					mrf.keyBlock.pushGAtom(rs.getInt("keyID"), n);
				}
				 */
				mrf.atoms.put(n.id, n);
				mrf.addAtom(n.id);
			}
			rs.close();
		} catch (Exception e) {
			ExceptionMan.handle(e);
		}

		sql = "SELECT * FROM " + relClauses;
		try (ResultSet rs = db.query(sql)) {
			//System.gc(); disabling this
			// load clauses
			UIMan.verbose(3, "Selecting clauses from DB...");
			UIMan.verboseInline(3, "skipping tautological clauses: ");
			while(rs.next()){
				if (Timer.hasTimedOut()) {
					rs.close();
					UIMan.verbose(3, "timed out here");
					ExceptionMan.die("Tuffy timed out reading atoms from DB");
				}
				GClause f = new GClause();
				f.parse(rs);
//				UIMan.verbose(3, "Selected clause " + f);
//				UIMan.verbose(3, "Time left " + Timer.secondsToTimeOut());
				if (!f.tautology) {
					mrf.clauses.add(f);
				} else {
					UIMan.verboseInline(3, ".");
				}
			}
			UIMan.verbose(3, "");
			rs.close();

			//System.gc(); disabling this
			//db.restoreAutoCommitState();
			UIMan.verbose(3, "Building indices...");
			mrf.buildIndices();
		} catch (Exception e) {
			ExceptionMan.handle(e);
		}
		return mrf;
	}


	public double calcMLELogCost(ArrayList<GAtom> _tomargin, Set<Component> components, BitSet world){

		double rs = 0;

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		for(Partition p : parts){

			ArrayList<GAtom> tomargin = new ArrayList<GAtom>();

			for(Integer i : p.mrf.getCoreAtoms()){
				if(p.mrf.atoms.get(i).isquery == false){
					tomargin.add(p.mrf.atoms.get(i));
				}
			}
			tomargin.retainAll(_tomargin);

			int[] cstate = new int[tomargin.size()];
			for(int i=0;i<cstate.length;i++){
				cstate[i] = 0;
			}
			cstate[0] = -1;

			//UIMan.println(":-( I am going to margin 2^" + cstate.length + " worlds!");

			Double metacost = null;

			while(true){

				cstate[0] = cstate[0] + 1;

				boolean exitFlag = false;

				for(int i = 0; i < cstate.length; i++){
					if(cstate[i] == 2){
						cstate[i] = 0;
						if(i+1 != cstate.length){
							cstate[i+1] ++;
						}else{
							exitFlag = true;
							break;
						}
					}else{
						break;
					}
				}

				if(exitFlag == true){
					break;
				}

				for(Integer atom : p.mrf.getCoreAtoms()){
					p.mrf.atoms.get(atom).truth = false;
				}

				BitSet conf = new BitSet(_tomargin.size()+1);
				for(int i = 0; i < cstate.length; i++){
					if(cstate[i] == 0){
						tomargin.get(i).truth = false;
					}else{
						tomargin.get(i).truth = true;
						conf.set(tomargin.get(i).id);
					}
				}

				for(Integer atom : p.mrf.getCoreAtoms()){
					if(world.get(atom)){
						p.mrf.atoms.get(atom).truth = true;
					}
				}

				if(metacost == null){
					metacost = -p.mrf.calcCosts();
				}else{
					metacost = logAdd(metacost, -p.mrf.calcCosts());
				}

			}

			rs = rs + metacost;

		}

		return rs;

	}


	public double calcLogPartitionFunction(ArrayList<GAtom> _tomargin, Set<Component> components){

		double rs = -1;

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		for(Partition p : parts){

			ArrayList<GAtom> tomargin = new ArrayList<GAtom>();

			for(Integer i : p.mrf.getCoreAtoms()){
				tomargin.add(p.mrf.atoms.get(i));
			}
			tomargin.retainAll(_tomargin);

			int[] cstate = new int[tomargin.size()];
			for(int i=0;i<cstate.length;i++){
				cstate[i] = 0;
			}
			cstate[0] = -1;

			UIMan.println(":-( I am going to margin 2^" + cstate.length + " worlds!");


			Double metacost = null;

			while(true){

				cstate[0] = cstate[0] + 1;

				boolean exitFlag = false;

				for(int i = 0; i < cstate.length; i++){
					if(cstate[i] == 2){
						cstate[i] = 0;
						if(i+1 != cstate.length){
							cstate[i+1] ++;
						}else{
							exitFlag = true;
							break;
						}
					}else{
						break;
					}
				}

				if(exitFlag == true){
					break;
				}

				for(Integer atom : p.mrf.getCoreAtoms()){
					p.mrf.atoms.get(atom).truth = false;
				}

				BitSet conf = new BitSet(_tomargin.size()+1);
				for(int i = 0; i < cstate.length; i++){
					if(cstate[i] == 0){
						tomargin.get(i).truth = false;
					}else{
						tomargin.get(i).truth = true;
						conf.set(tomargin.get(i).id);
					}
				}

				if(!wordLogPF.containsKey(p)){
					wordLogPF.put(p, new LinkedHashMap<BitSet, Double>());
				}
				wordLogPF.get(p).put(conf, -p.mrf.calcCosts());

				if(metacost == null){
					metacost = -p.mrf.calcCosts();
				}else{
					metacost = logAdd(metacost, -p.mrf.calcCosts());
				}

			}

			partLogPF.put(p, metacost);

			if(rs==-1){
				rs = metacost;
			}else{
				rs = rs + metacost;
			}
		}

		wholeLogPF = rs;

		return rs;

	}

	public LinkedHashMap<Partition, LinkedHashMap<BitSet, Double>> wordLogPF = new LinkedHashMap<Partition, LinkedHashMap<BitSet, Double>>();
	public LinkedHashMap<Partition, Double> partLogPF = new LinkedHashMap<Partition, Double>();
	public Double wholeLogPF = new Double(1);

	public double logAdd(double logX, double logY) {

		if (logY > logX) {
			double temp = logX;
			logX = logY;
			logY = temp;
		}

		if (logX == Double.NEGATIVE_INFINITY) {
			return logX;
		}

		double negDiff = logY - logX;
		if (negDiff < -200) {
			return logX;
		}

		return logX + java.lang.Math.log(1.0 + java.lang.Math.exp(negDiff)); 
	}

	/**
	 *  sample one word from the real probability distribution
	 * @param atoms
	 * @param components
	 * @return
	 */
	public BitSet SwordOfTruth(Collection<GAtom> atoms, ArrayList<Partition> parts){

		BitSet rs = new BitSet(atoms.size()+1);

		for(Partition p : parts){

			double logpdf = partLogPF.get(p);
			double sample = SeededRandom.getInstance().nextDouble();

			double acc = 0;
			BitSet bs = null;
			for(BitSet bs2 : wordLogPF.get(p).keySet()){
				double prob = Math.exp(wordLogPF.get(p).get(bs2) - logpdf);
				if(sample >= acc && sample <= acc + prob){
					bs = bs2;
					break;
				}
				acc = acc + prob;
			}

			rs.or(bs);

		}

		return rs;
	}

	public BitSet SwordOfRandom(Collection<GAtom> atoms, ArrayList<Partition> parts){

		BitSet rs = new BitSet(atoms.size()+1);

		for(GAtom atom : atoms){
			if(SeededRandom.getInstance().nextDouble() > 0.5){
				rs.set(atom.id);
			}
		}

		return rs;
	}

	public double getMAPProb(Collection<GAtom> atoms, Set<Component> components, BitSet world){

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		double rs = 0;

		for(Partition p : parts){

			BitSet conf = new BitSet(atoms.size()+1);
			for(Integer atom : p.mrf.getCoreAtoms()){
				if(world.get(atom)){
					conf.set(atom);
				}
			}

			rs += wordLogPF.get(p).get(conf);
		}

		rs -= wholeLogPF;

		return Math.exp(rs);
	}

	public double getMAPLogLazy(Collection<GAtom> atoms, ArrayList<Partition> parts, BitSet world){


		double rs = 0;

		for(Partition p : parts){

			for(Integer atom : p.mrf.getCoreAtoms()){

				if(world.get(atom)){
					p.mrf.atoms.get(atom).truth = true;
				}else{
					p.mrf.atoms.get(atom).truth = false;
				}

			}

			rs += -p.mrf.calcCostsFast();
		}

		//rs -= wholeLogPF;
		//
		//return Math.exp(rs);
		return rs;
	}

	public double getMAPLogCost(Collection<GAtom> atoms, Set<Component> components, BitSet world){

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		double rs = 0;

		for(Partition p : parts){

			BitSet conf = new BitSet(atoms.size()+1);
			for(Integer atom : p.mrf.getCoreAtoms()){
				if(world.get(atom)){
					conf.set(atom);
				}
			}

			rs += wordLogPF.get(p).get(conf);
		}

		//rs -= wholeLogPF;
		//
		//return Math.exp(rs);
		return rs;
	}

	public BitSet projectToMLEWorld(Collection<GAtom> atoms, ArrayList<Partition> parts, BitSet world){

		BitSet rs = new BitSet(atoms.size() + 1);

		for(Partition p : parts){
			for(Integer atom : p.mrf.getCoreAtoms()){
				if(p.mrf.atoms.get(atom).isquery && world.get(atom)){
					rs.set(atom);
				}
			}
		}

		return rs;
	}

	public double getMLEProb(Collection<GAtom> atoms, Set<Component> components, BitSet world){

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		double rs = 0;

		for(Partition p : parts){

			BitSet conf = new BitSet(atoms.size()+1);
			for(Integer atom : p.mrf.getCoreAtoms()){
				if(p.mrf.atoms.get(atom).truth == true){
					conf.set(atom);
				}
			}

			rs += wordLogPF.get(p).get(conf);
		}

		rs -= wholeLogPF;

		return Math.exp(rs);
	}

	public void naiveSampling(Collection<GAtom> atoms, Set<Component> components){

		// equivalant to non-partitioned version

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		LinkedHashMap<BitSet, myInt> sampleCache = new LinkedHashMap<BitSet, myInt>();

		double sum = 0;

		for(int nsample=0; nsample < 1000; nsample ++){

			BitSet rs = SwordOfTruth(atoms, parts);

			BitSet mle = this.projectToMLEWorld(atoms, parts, rs);

			if(!sampleCache.containsKey(mle)){
				sampleCache.put(mle, new myInt(0));
			}

			sampleCache.get(mle).addOne();
			sum ++;
		}


		ArrayList<BitSetIntPair> samples = new ArrayList<BitSetIntPair>();
		for(BitSet sample : sampleCache.keySet()){
			samples.add(new BitSetIntPair(sample, sampleCache.get(sample).value));
		}

		Collections.sort(samples, Collections.reverseOrder());

		int ct = 0;
		for(BitSetIntPair sample : samples){

			ct = ct + 1;
			if(ct > 10){
				break;
			}

			double mlelogpf = calcMLELogCost((ArrayList<GAtom>) atoms, components, sample.bitset);
			double prob = Math.exp(mlelogpf - wholeLogPF);

			UIMan.println(sample.integer/sum + "\t" + prob + "\t: " + sample.bitset); 
		}


	}

	public void weighedSampling(Collection<GAtom> atoms, Set<Component> components){

		// equivalant to non-partitioned version

		ArrayList<Partition> parts = new ArrayList<Partition>();
		for(Component c : components){
			parts.addAll(c.parts);
		}

		// latter change to more efficient case
		LinkedHashMap<BitSet, Double> sampleCache = new LinkedHashMap<BitSet, Double>();
		LinkedHashMap<BitSet, myInt> sampleCount = new LinkedHashMap<BitSet, myInt>();

		double sum = -1;
		double count = 0;

		double max = -1000000;
		double min = 1000000;

		for(int nsample=0; nsample < 100000; nsample ++){

			BitSet rs = SwordOfRandom(atoms, parts);
			BitSet mle = this.projectToMLEWorld(atoms, parts, rs);

			double logweight = this.getMAPLogLazy(atoms, parts, rs);

			if(max < logweight){
				max = logweight;
			}

			if(min > logweight){
				min = logweight;
			}

			if(!sampleCache.containsKey(mle)){
				sampleCache.put(mle, logweight);
				sampleCount.put(mle, new myInt(1));
			}else{	
				sampleCache.put(mle, logAdd(sampleCache.get(mle), logweight));
				sampleCount.get(mle).addOne();
			}

			if(sum == -1){
				sum = logweight;
			}else{
				sum = logAdd(sum, logweight);
			}

			count ++;
		}

		UIMan.println(">>> max weighted world = " + max);
		UIMan.println(">>> min weighted world = " + min);

		ArrayList<BitSetDoublePair> samples = new ArrayList<BitSetDoublePair>();
		for(BitSet sample : sampleCache.keySet()){
			samples.add(new BitSetDoublePair(sample, sampleCache.get(sample)));
		}

		Collections.sort(samples, Collections.reverseOrder());

		int ct = 0;
		for(BitSetDoublePair sample : samples){

			ct = ct + 1;
			if(ct > 100){
				break;
			}

			//double mlelogpf = calcMLELogCost((ArrayList<GAtom>) atoms, components, sample.bitset);
			//double prob = Math.exp(mlelogpf - wholeLogPF);

			double prob = -1;

			double est = Math.exp(sample.doub - sum );


			UIMan.println(est + "\t" + sampleCount.get(sample.bitset) + "\t" + sample.doub + "\t" + sum + "\t" +  prob + "\t: " + sample.bitset); 
		}


	}

	public void flushTopKAtomStates(Collection<GAtom> atoms, Set<Component> components, String relAtoms, boolean... isMAP){
		try {

			//double logpf = this.calcLogPartitionFunction((ArrayList<GAtom>) atoms, components);
			//UIMan.println(">>> Log Partition Function = " + logpf);

			//naiveSampling(atoms, components);
			//

			boolean weighted = true;

			//weighedSampling(atoms, components);

			if(weighted){

				weighedSampling(atoms, components);

			}else{

				ArrayList<Partition> parts = new ArrayList<Partition>();
				for(Component c : components){
					parts.addAll(c.parts);
				}

				Integer[] upperbound = new Integer[parts.size()];
				Integer[] cstate = new Integer[parts.size()];


				int ct = 0;


				Double[][] realMLECost = new Double[parts.size()][];
				for(Partition p : parts){
					upperbound[ct] = Math.min(p.mle_freq_cache.size(), Config.mleTopK);
					cstate[ct] = 0;

					realMLECost[ct] = new Double[upperbound[ct]];

					ct ++ ;
				}
				cstate[0] = -1;


				// begin brute force enumeration
				int solutionid = 0;
				while(true){

					cstate[0] = cstate[0] + 1;
					boolean exitFlag = false;
					for(int i=0;i<upperbound.length;i++){
						if(cstate[i] == upperbound[i]){

							//TODO: wrong sampling
							cstate[i] --;
							if(i+1 == upperbound.length){
								exitFlag = true;
							}else{
								cstate[i+1] ++;
							}
						}else{
							break;
						}
					}

					if(exitFlag == true){
						break;
					}

					for(GAtom atom : atoms){
						//TODO: is there any conflict?
						atom.truth = false;
					}


					BitSet finalbitset = new BitSet();
					double freq = 1;
					double realcost = 0;
					ct = 0;
					for(Partition p : parts){
						BitSetIntPair rs = p.mle_freq_cache.get(cstate[ct]);

						finalbitset.or(rs.bitset);
						freq = freq * (1.0*rs.integer);

						//if(Config.calcRealMLECost){
						//	realcost += realMLECost[ct][cstate[ct]];
						//}

						ct ++ ;
					}				

					//double mlelogpf = calcMLELogCost((ArrayList<GAtom>) atoms, components, finalbitset);
					//double prob = Math.exp(mlelogpf - wholeLogPF);

					double prob = -1.0;

					UIMan.println(freq + "\t" + prob + "\t: " + finalbitset); 


					//this.realCostCache.add(realcost);
					//System.out.println(freq + ": " + finalbitset);
					//this.flushAtomStatesFromBitMap(atoms, finalbitset, relAtoms, freq, "mle_rs_" + solutionid, isMAP);

					solutionid ++;

				}

			}


		} catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}


	public void flushAtomStatesFromBitMap(Collection<GAtom> atoms, BitSet bitset, String relAtoms, Double freq, String targetTable, boolean... isMAP){
		try {

			this.topKFreq.add(1.0*freq/1000);
			this.topKTables.add(targetTable);

			String fout = FileMan.getUniqueFileNameAbsolute();
			BufferedWriter writer = 
					new BufferedWriter(new OutputStreamWriter
							(new FileOutputStream(fout),"UTF8"), 50*1024*1024);
			int cnt = 0;
			UIMan.println("flushing states of " + atoms.size() + " atoms");
			//if(!Config.snapshot_mode){
			for(GAtom n : atoms){

				if(bitset.get(n.id)){
					writer.append(n.id + ", " + true + ", " + 1 + "\n");
					cnt ++;
				}else{
					writer.append(n.id + ", " + false + ", " + 0 + "\n");
					cnt ++;						
				}
			}


			writer.close();
			String relt = "tmp_ptruth";
			db.dropTable(relt);
			String sql = "CREATE TABLE " + relt + 
					"(fatomID INT, ftruth BOOL, fprob FLOAT)";
			db.update(sql);

			sql = "COPY " + relt + " FROM STDIN CSV";
			FileInputStream in = new FileInputStream(fout);
			PGConnection con = (PGConnection)db.getConnection();
			con.getCopyAPI().copyIn(sql, in);
			in.close();
			db.commit();
			FileMan.removeFile(fout);

			if(Config.using_greenplum){
				sql = "ALTER TABLE " + relAtoms + " SET DISTRIBUTED BY (atomID)";
				db.execute(sql);

				sql = "ALTER TABLE " + relt + " SET DISTRIBUTED BY (fatomID)";
				db.execute(sql);
			}

			if(isMAP.length > 0 && isMAP[0] == true){
				sql = "UPDATE " + relAtoms + " SET truth = ftruth, prob = NULL " +
						"FROM " + relt + " WHERE atomID = fatomID";
			}else{
				sql = "UPDATE " + relAtoms + " SET truth = NULL, prob = fprob " +
						"FROM " + relt + " WHERE atomID = fatomID";
			}
			db.update(sql);

			db.dropTable(targetTable);
			db.execute("CREATE TABLE " + targetTable + " AS SELECT * FROM " + relAtoms);

			db.dropTable(relt);

		} catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}


	/**
	 * Flush atoms states to the atom table.
	 */
	public void flushAtomStates(Collection<GAtom> atoms, String relAtoms, boolean... isMAP){
		try {
			String fout = FileMan.getUniqueFileNameAbsolute();
			BufferedWriter writer = 
					new BufferedWriter(new OutputStreamWriter
							(new FileOutputStream(fout),"UTF8"), 50*1024*1024);
			int cnt = 0;
			UIMan.println("flushing states of " + atoms.size() + " atoms");

			for(GAtom n : atoms){
				writer.append(n.id + ", " + n.truth + ", " + n.prob + "\n");
				if (n.truth) cnt++;
			}

			writer.close();
			String relt = "tmp_ptruth";
			db.dropTable(relt);
			String sql = "CREATE TABLE " + relt + 
					"(fatomID INT, ftruth BOOL, fprob FLOAT)";
			db.update(sql);

			sql = "COPY " + relt + " FROM STDIN CSV";
			FileInputStream in = new FileInputStream(fout);
			PGConnection con = (PGConnection)db.getConnection();
			con.getCopyAPI().copyIn(sql, in);
			in.close();
			db.commit();
			FileMan.removeFile(fout);

			if(Config.using_greenplum){
				sql = "ALTER TABLE " + relAtoms + " SET DISTRIBUTED BY (atomID)";
				db.execute(sql);

				sql = "ALTER TABLE " + relt + " SET DISTRIBUTED BY (fatomID)";
				db.execute(sql);
			}

			if(isMAP.length > 0 && isMAP[0] == true){
				sql = "UPDATE " + relAtoms + " SET truth = ftruth, prob = NULL " +
						"FROM " + relt + " WHERE atomID = fatomID";
			}else{
				sql = "UPDATE " + relAtoms + " SET truth = NULL, prob = fprob " +
						"FROM " + relt + " WHERE atomID = fatomID";
			}
			db.update(sql);

			db.dropTable(relt);

		} catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}

	private String atomToString(Predicate p, ResultSet rs, LinkedHashMap<Long,String> cmap){
		String line = p.getName() + "(";
		ArrayList<String> cs = new ArrayList<String>();
		try{
			for(int i=0; i<p.arity(); i++){
				String a = p.getArgs().get(i);
				Type t = p.getTypeAt(i);
				String v;
				if(!t.isNonSymbolicType()){
					v = cmap.get(rs.getLong(a));
				}else{
					v = rs.getString(a);
				}
				if(v.matches("^[0-9].*$") && !StringMan.escapeJavaString(v).contains(" ")){
					cs.add("" + StringMan.escapeJavaString(v) + "");
				}else{
					cs.add("\"" + StringMan.escapeJavaString(v) + "\"");
				}
			}
		}catch(Exception e){
			ExceptionMan.handle(e);
		}
		line += StringMan.commaList(cs) + ")";
		return line;
	}

	public void dumpTruthToFile(String relAtoms, String fout){
		LinkedHashMap<Long,String> cmap = db.loadIdSymbolMapFromTable();
		try {
			String sql;
			BufferedWriter bufferedWriter = FileMan.getBufferedWriterMaybeGZ(fout);
			for(Predicate p : mln.getAllPred()) {
				if(p.isImmutable()){
					sql = "SELECT * FROM " + p.getRelName() +
							" WHERE club=3 AND truth";
				}else{
					sql = "SELECT * FROM " + p.getRelName() + " pt " + 
							" WHERE (pt.club=3 OR pt.club=1) AND ( pt.truth OR " +
							" pt.id IN (SELECT tupleID FROM " + relAtoms + " ra " +
							" WHERE ra.truth AND ra.predID = " + p.getID() +
							") )" +
							" ORDER BY " + StringMan.commaList(p.getArgs());
				}
				ResultSet rs = db.query(sql);
				while(rs.next()) {
					String satom = atomToString(p, rs, cmap);
					bufferedWriter.append(satom + "\n");
				}
				rs.close();
			}
			bufferedWriter.close();
		} catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}

	public void dumpMLETruthToFile(String fout){

		Double all = .0;
		for(int i=0;i<this.topKFreq.size();i++){
			all += this.topKFreq.get(i);
		}

		for(int i=0;i<this.topKFreq.size();i++){
			String tableName = this.topKTables.get(i);
			Double freq = this.topKFreq.get(i);

			Double cost = -1.0;
			if(i < this.realCostCache.size()){
				cost = realCostCache.get(i);
			}

			String newfout = fout + "_mle_id_" + i + "_prob_" + String.format("%.5f", freq/all) + "_freq_" + freq + "_outof_" + all + "_realcost_" + cost;

			dumpTruthToFile(tableName, newfout);

		}
	}

	/**
	 * Generate a dummy data mover with a null MLN.
	 * @param db
	 * @return
	 */
	public static DataMover getDummyDataMover(RDB db) {
		MarkovLogicNetwork tmpMLN = new MarkovLogicNetwork();
		tmpMLN.setDB(db);
		DataMover datamover = new DataMover(tmpMLN);
		return datamover;
	}

	/**
	 * Propagate inference results to a predicate table.
	 */
	public void updateMasterPredTableFromAtoms(LinkedHashSet<String> relAtomsSet, 
			Predicate p){
		String sql;
		for(String relAtoms : relAtomsSet){
			UIMan.verbose(2, ">>> Updating master predicate table " + p.getRelName());
			sql = "UPDATE " + p.getRelName() + " SET prob = ra.prob, itruth = ra.truth " +
					"FROM " +relAtoms + " ra " +
					" WHERE id = ra.tupleID AND ra.predID = " + p.getID();
			db.execute(sql);
		}
	}

	/**
	 * Propagate inference results to a specified table.
	 * @param relAtomsTables
	 * @param p
	 * @param targetTable
	 * @param marginal
	 */
	public void populatePredTableWithInferenceResults(
			LinkedHashSet<String> relAtomsTables, Predicate p, 
			String targetTable, boolean marginal){

		db.dropTable(targetTable);
		db.execute("CREATE TABLE " + targetTable + " AS (SELECT * FROM " + 
				p.getRelName() + " WHERE 1=2)");		

		for(String relAtoms : relAtomsTables){
			String filter = "";
			if (!marginal) filter = " AND ra.truth";
			String sql = "INSERT INTO " + targetTable + 
					"(id, itruth, club, atomid, prob, useful, " + 
					StringMan.commaList(p.getArgs()) + ") \n" +
					" ( SELECT "
					+ "p.id, " + "ra.truth, p.club, p.atomid, ra.prob, p.useful, " + 
					StringMan.commaList(p.getArgs())
					+ " FROM " + p.getRelName() + " p, "
					+ relAtoms + " ra " + 
					" WHERE p.id = ra.tupleID AND ra.predID = " + p.getID()
					+ filter
					+ ") ";
			db.update(sql);
		}
	}
	
	/**
	 * Create MLN atom table with original predicate and constant names
	 */
	public void createAtomDescTable(String relAtoms, String relAtomDesc){
		LinkedHashMap<Long,String> cmap = db.loadIdSymbolMapFromTable();
		
		db.dropTable(relAtomDesc);
		db.dropView(relAtomDesc);
		String createSql = "CREATE TABLE " + relAtomDesc + "(\n";
		createSql += "atomId BIGINT,\n";
		createSql += "atomDesc CHARACTER VARYING);";
		db.update(createSql);

		String sql;
		try {
			for(Predicate p : mln.getAllPredOrderByName()){			
				if(p.isImmutable()) continue;

				String orderBy = " ORDER BY ";
				orderBy += StringMan.commaList(p.getArgs());				

				sql = "SELECT pt.*, ra.* FROM " + p.getRelName() + " pt, " + relAtoms + " ra " +
						" WHERE pt.id = ra.tupleID AND ra.predID = " + p.getID() +  
						orderBy;
				ResultSet rs = db.query(sql);
				while(rs.next()) {
					long atomId = rs.getLong("atomId");
					String satom = atomToString(p, rs, cmap);
					String line = atomId + "\t" + satom;
					UIMan.verbose(3, line);

					String insertSql;
					insertSql = "INSERT INTO " + relAtomDesc + " VALUES (";
					insertSql += atomId + ", '" + satom + "');";
					db.update(insertSql);
				}
				rs.close();
			}
		} catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}
	
	/**
	 * Create MLN clause table with original predicate and constant names
	 */
	public void createClauseDescTable(String relClauses, String relClauseDesc){
		db.dropTable(relClauseDesc);
		db.dropView(relClauseDesc);
		String sql = "CREATE TABLE " + relClauseDesc + "(\n";
		sql += "clauseId BIGINT,\n";
		sql += "clauseDesc CHARACTER VARYING);";
		db.update(sql);
		
		sql = "INSERT INTO " + relClauseDesc + " ";
		sql += "SELECT cid, string_agg(CASE WHEN litid > 0 THEN atomdesc ELSE CONCAT('!', atomdesc) END, ' v ' ORDER BY atomdesc) ";
		sql += "FROM " + Config.relAtomDesc + " a, (SELECT cid, UNNEST(lits) AS litid FROM " + relClauses + ") c ";
		sql += "WHERE ABS(c.litid) = a.atomid GROUP BY cid;";
		db.update(sql);
	}
	
	/**
	 * Dump MLN clause description table to a file
	 * @param relAtoms
	 * @param fout
	 */
	public void dumpClauseDescToFile(String relClauses, String relClauseDesc, String fout){
		BufferedWriter bufferedWriter = FileMan.getBufferedWriterMaybeGZ(fout);
		int digits = 4;
		String sql;
		try {
			sql = "SELECT c.weight, d.clauseDesc FROM " + relClauseDesc + " d, ";
			sql += relClauses + " c  WHERE c.cid = d.clauseId ORDER BY c.weight, d.clauseDesc";
			ResultSet rs = db.query(sql);
			while(rs.next()) {
				String clauseDesc = rs.getString("clauseDesc");
				double weight = rs.getDouble("weight");
				UIMan.verbose(3, UIMan.decimalRound(digits, weight) + "\t" + clauseDesc);
				bufferedWriter.append(UIMan.decimalRound(digits, weight) + "\t" + clauseDesc + "\n");
			}
			rs.close();
			bufferedWriter.close();
		}catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}
	
	public void dumpCNFToFile(String relAtoms, String relClauses, String fout){
		BufferedWriter bufferedWriter = FileMan.getBufferedWriterMaybeGZ(fout);
		String sql;
		try {
			sql = "SELECT count(*) as count FROM " + relClauses;
			ResultSet rs = db.query(sql);
			rs.next();
			int numClauses = rs.getInt("count");
			if (numClauses > Config.maxClausesToCNF) {
				ExceptionMan.die("Too many clauses in grounded CNF for unit prop");
			}
			sql = "SELECT count(*) as count FROM " + relAtoms;
			rs = db.query(sql);
			rs.next();
			int numAtoms = rs.getInt("count");
//			UIMan.verbose(3, "p cnf " + numAtoms + " " + numClauses);
			bufferedWriter.append( "p cnf " + numAtoms + " " + numClauses + "\n");
			
			sql = "SELECT weight, array_length(list, 1) as len, array_to_string(list, ' ') as lits FROM " + relClauses +
					" WHERE abs(weight) >= " + Config.hard_weight;
			rs = db.query(sql);
			while(rs.next()) {
				String lits = rs.getString("lits");
				int len = rs.getInt("len");
				double weight = rs.getDouble("weight");
				if (weight <= -Config.hard_weight && len == 1) {
					if (lits.charAt(0) == '-') {
						lits = lits.substring(1);
					} else {
						lits = "-" + lits;
					}
				}
//				UIMan.verbose(3, lits + " 0");
				bufferedWriter.append(lits + " 0\n");
			}
			rs.close();
			bufferedWriter.close();
		}catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}
	
	public void dumpWCNFToFile(String relAtoms, String relClauses, String fout){
		BufferedWriter bufferedWriter = FileMan.getBufferedWriterMaybeGZ(fout);
		int digits = 4;
		String sql;
		try {
			sql = "SELECT count(*) as count FROM " + relClauses;
			ResultSet rs = db.query(sql);
			rs.next();
			int numClauses = rs.getInt("count");
			sql = "SELECT count(*) as count FROM " + relAtoms;
			rs = db.query(sql);
			rs.next();
			int numAtoms = rs.getInt("count");
//			UIMan.verbose(3, "p wcnf " + numAtoms + " " + numClauses + " " + UIMan.decimalRound(digits, Config.hard_weight));
			bufferedWriter.append( "p wcnf " + numAtoms + " " + numClauses + " " + UIMan.decimalRound(digits, Config.hard_weight) + "\n");
			
			sql = "SELECT weight, array_length(lits, 1) as len, array_to_string(lits, ' ') as lits FROM " + relClauses +
					" ORDER BY cid";
			rs = db.query(sql);
			while(rs.next()) {
				String lits = rs.getString("lits");
				int len = rs.getInt("len");
				double weight = rs.getDouble("weight");
				if (weight > Config.hard_weight) {
					weight = Config.hard_weight;
				} else if (weight <= -Config.hard_weight && len == 1) {
					weight = Config.hard_weight;
					if (lits.charAt(0) == '-') {
						lits = lits.substring(1);
					} else {
						lits = "-" + lits;
					}
				}
//				UIMan.verbose(3, UIMan.decimalRound(digits, weight) + " " + lits);
				bufferedWriter.append(UIMan.decimalRound(digits, weight) + " " + lits + " 0\n");
			}
			rs.close();
			bufferedWriter.close();
		}catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}
	
	public void writeMRFClausesToBuffer(MRF mrf, String cbuffer){
		db.dropTable(cbuffer);
		db.dropView(cbuffer);
		String sql = "CREATE TABLE " + cbuffer + "(list INT[], weight FLOAT8, "
				+ "fcid INT, ffcid text)";
		db.update(sql);
		
		for (GClause cee : mrf.clauses) {
			StringBuilder clauseStr = new StringBuilder();
			clauseStr.append(cee.lits[0]);
			for (int i = 1; i < cee.lits.length; i++) {
				clauseStr.append(", ").append(cee.lits[i]);
			}
			String iql = "INSERT INTO " + cbuffer + " (list, weight) " +
					" VALUES (array[ " + clauseStr + "], " +
					cee.weight + ");";
			db.update(iql);
		}
	}
	
	public void writeMRFClausesToTable(MRF mrf, String relClauses){
		db.dropTable(relClauses);
		db.dropView(relClauses);
		String sql = "CREATE TABLE " + relClauses + "(\n";
		sql += "cid INT,\n";
		sql += "lits INT[],\n";
		sql += "weight FLOAT8,\n";
		sql += "fcid INT[],\n";
		sql += "ffcid TEXT[]);";
		db.update(sql);
		
		for (GClause cee : mrf.clauses) {
			StringBuilder clauseStr = new StringBuilder();
			clauseStr.append(cee.lits[0]);
			for (int i = 1; i < cee.lits.length; i++) {
				clauseStr.append(", ").append(cee.lits[i]);
			}
			String iql = "INSERT INTO " + relClauses + " (cid, lits, weight) " +
					" VALUES (" + cee.id +
					", array[ " + clauseStr + "], " +
					cee.weight + ");";
			db.update(iql);
		}
	}

	/**
	 * Dump marginal inference results to a file
	 * @param relAtoms
	 * @param fout
	 */
	public void dumpProbsToFile(String relAtoms, String fout){
		BufferedWriter bufferedWriter = FileMan.getBufferedWriterMaybeGZ(fout);
		LinkedHashMap<Long,String> cmap = db.loadIdSymbolMapFromTable();
		int digits = 4;
		String sql;
		try {
			for(Predicate p : mln.getAllPredOrderByName()){			
				if(p.isImmutable()) continue;
				String atomTypeCond = (Config.mcsat_output_hidden_atoms ?					
						" " : " AND (club=1 OR club=3) ");
				String orderBy = " ORDER BY ";
				switch(Config.mcsat_output_order){
				case PRED_ARGS:
					orderBy += StringMan.commaList(p.getArgs());				
					break;
				case PROBABILITY:
					orderBy += " ra.prob DESC ";
				}
				sql = "SELECT pt.*, ra.*, ra.prob as raprob FROM " + p.getRelName() + " pt, " + relAtoms + " ra " +
						" WHERE pt.id = ra.tupleID AND ra.predID = " + p.getID() +  
						" AND ra.prob >= " + Config.marginal_output_min_prob + " " +
						atomTypeCond + orderBy;
				ResultSet rs = db.query(sql);
				while(rs.next()) {
					double prob = rs.getDouble("raprob");
					double prior = rs.getDouble("prior");
					if(rs.wasNull()){
						prior = -1;
					}
					String satom = atomToString(p, rs, cmap);
					String line = null;
					if(Config.output_prolog_format){
						line = "tuffyPrediction(" + UIMan.decimalRound(digits, prob) +
								", " + satom + ").";
					}else{
						line = UIMan.decimalRound(digits, prob) + "\t" + satom;
					}
					if(Config.output_prior_with_marginals && prior >= 0){
						line += " // prior = " + UIMan.decimalRound(digits, prior);
						line += " ; delta = " + UIMan.decimalRound(digits, prob - prior);
					}
					//UIMan.verbose(3, line);
					UIMan.println(line);
					bufferedWriter.append(line + "\n");
				}
				rs.close();
			}
			bufferedWriter.close();
		}catch (Exception e) {
			ExceptionMan.handle(e);
		}
	}


	public void dumpSampleLog(String fout){
		/*
		sig + "\t" + 
				this.sampleAlgo.getName() + "\t" + 
				this.mrf.atoms.size() + "\t" +
				Timer.elapsedMilliSeconds(this + "") + "\t" +
				atom.id + "\t" +
				atom.tallyTrueLogWeight + "\t" +
				atom.tallyLogWeight + "\t" + 
				atom.tallyTrueFreq + "\t" +
				atom.tallyFreq + "\t" +
				Math.exp(atom.tallyTrueLogWeight - atom.tallyLogWeight) + "\t" + 
				atom.tallyTrueFreq/atom.tallyFreq
			*/	
		
		LinkedHashMap<Long,String> cmap = db.loadIdSymbolMapFromTable();
		
		db.dropTable("sample");
		db.update("CREATE TABLE sample (nsample int, sig text, sampleAlgo TEXT, compsize INT," +
				" time FLOAT8, atomid INT, pid INT, tallytruelog FLOAT8, tallylog FLOAT8, tallytrue FLOAT8, tally FLOAT8," +
				"prob_weight FLOAT, prob_true FLOAT, maxweight FLOAT, treewidth FLOAT, avgDegree FLOAT);");
		
		System.out.println(">>> Loading sample logs...");
		

		FileInputStream in;
		try {
			in = new FileInputStream(Config.samplerWriterPath);
			PGConnection con = (PGConnection) db.getConnection();
			String sql = "COPY " + "sample" + " FROM STDIN";
			con.getCopyAPI().copyIn(sql, in);
			in.close();
			db.analyze("sample");
		} catch (Exception e) {
			e.printStackTrace();
		} 
	
		BufferedWriter bufferedWriter = FileMan.getBufferedWriterMaybeGZ(fout);
		
		try {
			bufferedWriter.write("pred\tpid\tnsample\tsig\tsamplealgo\tcompsize\ttime\tprob_weight\tprob_true\tmaxweight\ttreewidth\tavgdegree\n");
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		
		for(Predicate p : mln.getAllPredOrderByName()){		
			
			String sql = "select pt.*, t1.* from " + p.getRelName() 
					+ " pt, sample t1 WHERE pt.atomid=t1.atomid;";
			
			
			try {
				ResultSet rs = db.query(sql);
				while(rs.next()) {
					
					String satom = atomToString(p, rs, cmap);
					
					int nsample = rs.getInt("nsample");
					String sig = rs.getString("sig");
					String sampleAlgo = rs.getString("sampleAlgo");
					int compsize = rs.getInt("compsize");
					int pid = rs.getInt("pid");
					float time = rs.getFloat("time");
					float prob_weight = rs.getFloat("prob_weight");
					float prob_freq = rs.getFloat("prob_true");
					float maxweight = rs.getFloat("maxweight");
					float treewidth = rs.getFloat("treewidth");
					float avgDegree = rs.getFloat("avgDegree");
					
					
					bufferedWriter.write(satom+"\t" + pid + "\t" + nsample+"\t"+sig+"\t"+sampleAlgo+
							"\t"+compsize+"\t"+time+"\t"+prob_weight+"\t"+prob_freq+"\t" + maxweight + "\t" + treewidth + "\t" + avgDegree + "\n");
					
					/*String line = null;
					if(Config.output_prolog_format){
						line = "tuffyPrediction(" + UIMan.decimalRound(digits, prob) +
							", " + satom + ").";
					}else{
						line = UIMan.decimalRound(digits, prob) + "\t" + satom;
					}
					if(Config.output_prior_with_marginals && prior >= 0){
						line += " // prior = " + UIMan.decimalRound(digits, prior);
						line += " ; delta = " + UIMan.decimalRound(digits, prob - prior);
					}
					bufferedWriter.append(line + "\n");*/
				}
				rs.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
			
		}
		
		try {
			bufferedWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}


}
