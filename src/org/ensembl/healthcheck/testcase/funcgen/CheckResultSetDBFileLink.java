package org.ensembl.healthcheck.testcase.funcgen;

import org.ensembl.healthcheck.DatabaseRegistryEntry;
import org.ensembl.healthcheck.ReportManager;
import org.ensembl.healthcheck.Team;
import org.ensembl.healthcheck.testcase.Priority;
import org.ensembl.healthcheck.testcase.SingleDatabaseTestCase;
import org.ensembl.healthcheck.util.DBUtils;

import java.io.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import junit.framework.Test;
import org.apache.commons.lang.StringUtils;

public class CheckResultSetDBFileLink extends SingleDatabaseTestCase {

	public CheckResultSetDBFileLink() {
		addToGroup("post_regulatorybuild");
		addToGroup("funcgen");//do we need this group and the funcgen-release group?
		addToGroup("funcgen-release");
		setTeamResponsible(Team.FUNCGEN);

		setDescription("Checks if the binary signal (col) files exist for relevant ResultSets\n" +
				"Also checks dbfile_data_root subdirs to see if there are still DISPLAYABLE or if they support a regualtory build\n");
		
		
		setPriority(Priority.AMBER);
		setEffect("Signal tracks will not display in the browser.\n" + 
				"NOTE: RegulatorySets does something similar, but from the DataSet perspective\n " +
				"\tHence, consider those HC results first, before fixing these!");
		setFix("Re-create files or check file names manually.");		
		
	}

	private String getSupportedRegulatoryFeatureSet(Connection con, String subdirName){
		
		String regFsetSQL = "SELECT fs.name from result_set rs, supporting_set ss, data_set ds, " + 
		"supporting_set ss1, data_set ds1, feature_set fs WHERE " +
		"rs.result_set_id=ss.supporting_set_id and ss.type='result' and ss.data_set_id=ds.data_set_id " +
		"AND ds.feature_set_id=ss1.supporting_set_id and ss1.type='feature' and " + 
		"ss1.data_set_id=ds1.data_set_id and ds1.feature_set_id=fs.feature_set_id and " +
		"fs.type='regulatory' and fs.name not rlike '.*_v[0-9]+$' and rs.name='" + subdirName + "'";
		String regFset = null;
		
		try{
			Statement stmt = con.createStatement();
			ResultSet supportedRegFset = stmt.executeQuery(regFsetSQL);
		
			if((supportedRegFset != null) && supportedRegFset.next()){
				regFset = supportedRegFset.getString(1);	
				//doesn't matter if we get duplicate entries here based on
				//redundant rset names. catch reundant set before here
			}
		}
		catch(SQLException e){
			e.printStackTrace();
		}
			
		return regFset;
	}
	
	public boolean run(DatabaseRegistryEntry dbre) {
		
		boolean result=true;
		Connection con = dbre.getConnection();
	
		try {
			Statement stmt = con.createStatement();
			int MAX_REPORT=5; //Only out 5 problems by default
			HashMap<String, String> rSetDBLinks = new HashMap<String, String>();
			HashMap<String, String> rSetStates  = new HashMap<String, String>();
			HashMap<String, String> rSetRFSets  = new HashMap<String, String>();
			ArrayList removeableRsets           = new ArrayList();

			
			String rsetInfoSQL = "SELECT rs.name, dbf.path, s1.name from result_set rs left join dbfile_registry dbf "+ 
								"ON rs.result_set_id=dbf.table_id and dbf.table_name='result_set' left join " + 
								"(select s.table_id, sn.name from status s, status_name sn where " + 
								"s.status_name_id=sn.status_name_id and s.table_name='result_set' and sn.name='DISPLAYABLE') s1 " +
								"ON rs.result_set_id=s1.table_id";
		
			ResultSet rsetInfo = stmt.executeQuery(rsetInfoSQL);
			String rsetStatus, rsetPath, rsetName, regFset;
			String infoString = "";
			
			while ((rsetInfo != null) && rsetInfo.next()) {
				rsetName   = rsetInfo.getString(1); 
				rsetPath   = rsetInfo.getString(2);
				rsetStatus = rsetInfo.getString(3);
				regFset = this.getSupportedRegulatoryFeatureSet(con, rsetName);
			
				//TEST IF WE HAVE SEEN A REDUNDANTLY NAMED RESULT_SET
				if(rSetDBLinks.containsKey(rsetName)){
					//bail out here or continue?
					//or could mark for deletion as we could have >2 
					ReportManager.problem(this, con, "Found redundant result_set naming:\t" + rsetName +
							"\nEither rectify in DB or updated HC to account for result_set unique key");
					return false; //bail out as results maybe unsafe
				}
				
				if( (rsetPath != null) || 
					(rsetStatus != null) ||
					(regFset != null) ){
						
					rSetDBLinks.put(rsetName, rsetPath);
					rSetStates.put(rsetName, rsetStatus);
					rSetRFSets.put(rsetName, regFset);
				}
				else{
					removeableRsets.add(rsetName);	
				}
			}

			
			if(removeableRsets.size() > 0){
				//Should this be info instead?
				ReportManager.problem(this, con, "Found " + removeableRsets.size() + 
						" 'removeable' result_sets i.e. not DISPLAYABLE, not in build and has no dbfile_registry.path:\n\t" +
						StringUtils.join(removeableRsets, "\n\t") + "\n");
				result = false;
			}
			
			int numRsets = rSetDBLinks.size();
			
			//Get Base Folder
			ResultSet rsetDBDataRoot = stmt.executeQuery("SELECT meta_value from meta where meta_key='dbfile.data_root'");
			String problemString; 	//For easier interpretation/reporting, build 1 problem string per result_set/subDir, 
			
			if((rsetDBDataRoot != null) && rsetDBDataRoot.next()){
				String root_dir  = rsetDBDataRoot.getString(1);
						
				//TEST EXISTING DIRECTORIES ARE RESULT SETS
				File result_feature_dir_f = new File(root_dir + "/result_feature");
			
				if(result_feature_dir_f.exists()){
					String[] subDirs = result_feature_dir_f.list();
					String rsetSQL;
					ArrayList subdirProblems = new ArrayList();
					
					
					for(int i=0; i<subDirs.length; i++){
						problemString = "";
						rsetSQL = "SELECT result_set_id from result_set where name='" + subDirs[i] + "'";
						ResultSet subdirRsetIDs = stmt.executeQuery(rsetSQL);
						
						if((rsetDBDataRoot != null) && subdirRsetIDs.next()){
							String rsetID         = subdirRsetIDs.getString(1);
							//logger.fine("Found result_feature subdir:\t" + subDirs[i] + " with rset id\t" + rsetID);

							if(subdirRsetIDs.next()){
								problemString += "\tCannot find unique result_set. Check manually or update HC\n";
							}
							
							//CATCH SUBDIRS WHICH FOR RESULT_SETS WITHOUT DBFILE_REGISTRY/DISPLAYABLE ENTRY OR IN BUILD
							if(removeableRsets.contains(subDirs[i])){
															problemString += "\tAppears to be 'removeable' i.e. not DISPLAYABLE, not in build and has no dbfile_registry.path.\n";
							}
						}
						else{
							problemString += "\tCannot find result_set.\n";
						}
					
						
						if(! problemString.equals("")){
							subdirProblems.add(subDirs[i] + " result_feature subdir has problems:\n" + problemString);
						}
					}
					
					
					int numProbs = subdirProblems.size();
					
					if(numProbs != 0){
						ReportManager.problem(this, con, "Found " + numProbs + " result_feature subdirs with problems.");
						result = false;
											
						for(int i=0; i<numProbs; i++){
																
							if(i >= MAX_REPORT){
								//Both these seem to report even with when restricting to -output problem?
								ReportManager.info(this, con, subdirProblems.get(i).toString());
							}
							else{
								ReportManager.problem(this, con, subdirProblems.get(i).toString());
							}	
						}
					}
					else{
						ReportManager.info(this, con, "Found 0 result_feature subdirs with problems.");					
					}
					
				}
				else{
					ReportManager.problem(this, con, "Cannot test if result_set dirs are valid as parent directory does not exist:\t" + 
							root_dir + "/result_feature");
					result = false;
					//Don't return here as rsetPaths in DB may now be pointing to as different path
				}
								
				if(numRsets == 0){
					ReportManager.problem(this, con, "dbfile_root is defined in the meta table but found no result_sets can be found");
					result = false;					//Could return here?
				
				}
				else{ // NOW CHECK EXISTING RESULT SETS
					File root_dir_f = new File(root_dir);
														
					if(root_dir_f.exists()){
						ArrayList rsetProblems = new ArrayList();
						Iterator<String> dbLinkIt = rSetDBLinks.keySet().iterator();
						Object tmpObject;
						
						while(dbLinkIt.hasNext()){
							rsetName   = dbLinkIt.next().toString();
							problemString = "";
							//toString on null was failing silently here!
							rsetPath   = ( (tmpObject = rSetDBLinks.get(rsetName)) == null) ? "NO DBFILE_REGISTRY PATH" : tmpObject.toString();
							rsetStatus = ( (tmpObject = rSetStates.get(rsetName)) == null) ? "NOT DISPLAYABLE" : tmpObject.toString();
							regFset    = ( (tmpObject = rSetRFSets.get(rsetName)) == null) ? "NOT IN BUILD" : tmpObject.toString();
													
							//Report all these together for easier interpretation	
							if( rsetPath.equals("NO DBFILE_REGISTRY PATH") ||
								rsetStatus.equals("NOT DISPLAYABLE") ||
								regFset.equals("NOT IN BUILD") ){
								
								problemString += "\tdbfile_registry.path:\t" + rsetPath + "\n\t" +
										"IS " + rsetStatus + "\n\t" + "Supports:\t" + regFset + "\n";
							}
					
							
							if(! rsetPath.equals("NO DBFILE_REGISTRY PATH")){// NOW TEST COL FILES
								String rSetFinalPath = root_dir + rsetPath;
								File rsetFolder = new File(rSetFinalPath);
															
								if(rsetFolder.exists()){
									String[] windows = {"30","65","130","260","450","648","950","1296"}; 
								
									for(int i=0;i<windows.length;i++){
										String rsetWindowFileName = rSetFinalPath + "/result_features." + rsetName + "." + windows[i] + ".col";
										File rsetWindowFile = new File(rsetWindowFileName);
									
										if(rsetWindowFile.exists()){
											if(rsetWindowFile.length() == 0){
												problemString += "\tEmpty file:\t" + rsetWindowFileName + "\n";
											}
										} else {
											problemString += "\tFile does not exist:\t" + rsetWindowFileName + "\n";
										}
									}
																
								} else {
									problemString += "\tdbfile_registry.path does not exist:\t" + rSetFinalPath + "\n";
								}
							}
													
							if(! problemString.equals("")){
								rsetProblems.add(rsetName + " ResultSet has problems:\n" + problemString);
							}
						}

						
						int numProbs = rsetProblems.size();
					
						if(numProbs != 0){
							ReportManager.problem(this, con, "Found " + numProbs + " ResultSets with problems.\n");
							result = false;
												
							for(int i=0; i<numProbs; i++){
																	
								if(i >= MAX_REPORT){
									//Both these seem to report even with when restricting to -output problem?
									ReportManager.info(this, con, rsetProblems.get(i).toString());
								}
								else{
									ReportManager.problem(this, con, rsetProblems.get(i).toString());
								}	
							}
						}
						else{
							ReportManager.info(this, con, "Found 0 ResultSets with problems.");					
						}
					} 
					else {
						ReportManager.problem(this, con, "Found " + numRsets + " result_sets but " + 
								"dbfile.data_root does not seem to be valid:\t" + root_dir);
						result = false; //could return here?
					}
				}
			} else {
				
				if(numRsets == 0){
					//could sanity check we don't have a build here?
					ReportManager.info(this, con, "Found no result_sets or dbfile.data_root");
				}
				else{				
					ReportManager.problem(this, con, "Found " + numRsets + 
							"result_sets but no dbfile.data_root meta key");
					result = false; 	//could return here?
				}
			}				
		} catch (SQLException e){
			e.printStackTrace();
		}
		
		return result;
	}
}