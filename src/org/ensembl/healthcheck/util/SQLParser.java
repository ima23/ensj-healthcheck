/*
  Copyright (C) 2004 EBI, GRL
 
  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.
 
  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.
 
  You should have received a copy of the GNU Lesser General Public
  License along with this library; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.ensembl.healthcheck.util;

import java.sql.*;
import java.util.*;
import java.io.*;
import java.util.logging.*;

/**
 * Utilities for parsing a SQL file.
 */
public class SQLParser {
  
  /** Internal list of lines parsed from the file */
  private List lines;
  
  /** Logger object to use */
  protected static Logger logger = Logger.getLogger("HealthCheckLogger");
  
  /** Creates a new instance of SQLParser */
  public SQLParser() {
    lines = new ArrayList();
  }
  
  // -------------------------------------------------------------------------
  /**
   * Parse a file containing SQL.
   * @param fileName The name of the file to parse.
   * @return A list of SQL commands read from the file.
   * @throws FileNotFoundException If fileName cannot be found.
   */
  public List parse(String fileName) throws FileNotFoundException {
    
    File file = new File(fileName);
    if (!file.exists()) {
      throw new FileNotFoundException();
    }
    
    // the file may have SQL statements spread over several lines
    // so line in file != SQL statement
    StringBuffer sql = new StringBuffer();
    BufferedReader br = new BufferedReader(new FileReader(file));
    
    String line;
    try {
      while ((line = br.readLine()) != null) {
        
        line = line.trim();
        
        // skip comments and blank lines
        if (line.startsWith("#") || line.length() == 0) {
          continue;
        }
        
        // remove trailing comments
        int commentIndex = line.indexOf("#");
        if (commentIndex > -1) {
          line = line.substring(0, commentIndex);
        }
        
        if (line.endsWith(";")) {  // if we've hit a semi-colon, that's the end of the SQL statement
          sql.append(line.substring(0, line.length()-1)); // chop off ;
          lines.add(sql.toString());
          logger.finest("Added SQL statement beginning " + Utils.truncate(sql.toString(), 80, false));
          sql = new StringBuffer(); // ready for the next one
          
        } else {
          
          sql.append(line);
          
        }
      }
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
    
    return lines;
    
  }
  
  // -------------------------------------------------------------------------
  /** 
   * Fill a SQL Statement with a set of batch commands from the SQL file.
   * @param stmt The statement to be filled.
   * @return A statement with addBatch() called for each command in the parsed SQL file.
   */
  public Statement populateBatch(Statement stmt) {
    
    if (stmt == null) {
      logger.severe("SQLParser: input statement is NULL");
    }
    
    Statement result = stmt;
    
    Iterator it = lines.iterator();
    while (it.hasNext()) {
      String line = (String)it.next();
      try {
        result.addBatch(line);
        logger.finest("Added line begining " + Utils.truncate(line, 80, false) + " to batch");
      } catch (SQLException se) {
        se.printStackTrace();
      }
    }
    
    return result;
    
  }
  
  // -------------------------------------------------------------------------
  
  /**
   * Getter for property lines.
   * @return Value of property lines.
   */
  public java.util.List getLines() {
    return lines;
  }
  
  /**
   * Setter for property lines.
   * @param lines New value of property lines.
   */
  public void setLines(java.util.List lines) {
    this.lines = lines;
  }
  
  // -------------------------------------------------------------------------
  /**
   * Dump the SQL commands to stdout.
   */
  public void printLines() {
    
    Iterator it = lines.iterator();
    while (it.hasNext()) {
      System.out.println((String)it.next());
    }
    
  }
  
  // -------------------------------------------------------------------------
  
}
