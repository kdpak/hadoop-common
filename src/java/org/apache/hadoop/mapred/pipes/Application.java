/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred.pipes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

/**
 * This class is responsible for launching and communicating with the child 
 * process.
 */
class Application {
  private static final Log LOG = LogFactory.getLog(Application.class.getName());
  private ServerSocket serverSocket;
  private Process process;
  private Socket clientSocket;
  private OutputHandler handler;
  private BinaryProtocol downlink;

  /**
   * Start the child process to handle the task for us.
   * @param conf the task's configuration
   * @param output the collector to send output to
   * @param reporter the reporter for the task
   * @param outputKeyClass the class of the output keys
   * @param outputValueClass the class of the output values
   * @throws IOException
   * @throws InterruptedException
   */
  Application(JobConf conf, OutputCollector output, Reporter reporter,
              Class outputKeyClass, Class outputValueClass
              ) throws IOException, InterruptedException {
    serverSocket = new ServerSocket(0);
    Map<String, String> env = new HashMap<String,String>();
    env.put("hadoop.pipes.command.port", 
            Integer.toString(serverSocket.getLocalPort()));
    List<String> cmd = new ArrayList<String>();
    String executable = DistributedCache.getLocalCacheFiles(conf)[0].toString();
    FileUtil.chmod(executable, "a+x");
    cmd.add(executable);
    process = runClient(cmd, env);
    clientSocket = serverSocket.accept();
    handler = new OutputHandler(output, reporter);
    WritableComparable outputKey = (WritableComparable)
      ReflectionUtils.newInstance(outputKeyClass, conf);
    Writable outputValue = (Writable) 
      ReflectionUtils.newInstance(outputValueClass, conf);
    downlink = new BinaryProtocol(clientSocket, handler, 
                                  outputKey, outputValue, conf);
    downlink.start();
    downlink.setJobConf(conf);
  }

  /**
   * Get the downward protocol object that can send commands down to the
   * application.
   * @return the downlink proxy
   */
  DownwardProtocol getDownlink() {
    return downlink;
  }

  /**
   * Wait for the application to finish
   * @return did the application finish correctly?
   * @throws Throwable
   */
  boolean waitForFinish() throws Throwable {
    return handler.waitForFinish();
  }

  /**
   * Abort the application and wait for it to finish.
   * @param t the exception that signalled the problem
   * @throws IOException A wrapper around the exception that was passed in
   */
  void abort(Throwable t) throws IOException {
    LOG.info("Aborting because of " + StringUtils.stringifyException(t));
    try {
      downlink.abort();
    } catch (IOException e) {
      // IGNORE cleanup problems
    }
    try {
      handler.waitForFinish();
    } catch (Throwable ignored) {
      process.destroy();
    }
    IOException wrapper = new IOException("pipe child exception");
    wrapper.initCause(t);
    throw wrapper;      
  }
  
  /**
   * Clean up the child procress and socket.
   * @throws IOException
   */
  void cleanup() throws IOException {
    serverSocket.close();
    try {
      downlink.closeConnection();
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }      
  }

  /**
   * A thread to copy an input stream to an output stream.
   * Errors cause the copy to stop and are not reported back.
   * The input stream is closed when the thread exits. The output stream
   * is not closed.
   */
  private static class OutputCopier extends Thread {
    InputStream in;
    OutputStream out;
    OutputCopier(String name, InputStream in, OutputStream out) {
      super(name);
      this.in = in;
      this.out = out;
    }
    public void run() {
      byte[] buffer = new byte[65536];
      try {
        while (true) {
          int size = in.read(buffer);
          if (size == -1) {
            break;
          }
          out.write(buffer, 0, size);
        }
      } catch (IOException ie) {
      } finally {
        try {
          in.close();
        } catch (IOException ie) { }
      }
    }
  }

  /**
   * Run a given command in a subprocess, including threads to copy its stdout
   * and stderr to our stdout and stderr.
   * @param command the command and its arguments
   * @param env the environment to run the process in
   * @return a handle on the process
   * @throws IOException
   */
  static Process runClient(List<String> command, 
                           Map<String, String> env) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    if (env != null) {
      builder.environment().putAll(env);
    }
    Process result = builder.start();
    result.getOutputStream().close();
    new OutputCopier("pipes-stdout", result.getInputStream(), 
                     System.out).start();
    new OutputCopier("pipes-stderr", result.getErrorStream(), 
                     System.err).start();
    return result;
  }

}
