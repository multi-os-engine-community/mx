/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.mxtool.compilerserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class CompilerDaemon {

    protected void logf(String commandLine, Object... args) {
        if (verbose) {
            System.err.printf(commandLine, args);
        }
    }

    private boolean verbose = false;
    private boolean running;
    private ThreadPoolExecutor threadPool;

    public void run(String[] args) throws Exception {
        if (args.length == 2) {
            if (args[0].equals("-v")) {
                verbose = true;
            } else {
                usage();
            }
        } else if (args.length != 1) {
            usage();
        }

        // create socket
        int port = Integer.parseInt(args[args.length - 1]);
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        logf("Started server on port %d\n", port);

        int threadCount = Runtime.getRuntime().availableProcessors();
        threadPool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable);
            }
        });

        running = true;
        while (running) {
            threadPool.submit(new Connection(serverSocket.accept(), createCompiler()));
        }
        serverSocket.close();
    }

    private static void usage() {
        System.err.println("Usage: [ -v ] port");
        System.exit(1);
    }

    abstract Compiler createCompiler();

    interface Compiler {
        int compile(String[] args) throws Exception;
    }

    String join(String delim, String[] strings) {
        if (strings.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(strings[0]);
        for (int i = 1; i < strings.length; i++) {
            sb.append(delim);
            sb.append(strings[i]);
        }
        return sb.toString();
    }

    public class Connection implements Runnable {

        private final Socket connectionSocket;
        private final Compiler compiler;

        public Connection(Socket connectionSocket, Compiler compiler) {
            this.connectionSocket = connectionSocket;
            this.compiler = compiler;
        }

        public void run() {
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream(), "UTF-8"));
                OutputStreamWriter output = new OutputStreamWriter(connectionSocket.getOutputStream(), "UTF-8");

                try {
                    String commandLine = input.readLine();
                    if (commandLine.length() == 0) {
                        logf("Shutting down\n");
                        running = false;
                        while (threadPool.getActiveCount() > 1) {
                            threadPool.awaitTermination(50, TimeUnit.MILLISECONDS);
                        }
                        System.exit(0);
                    } else {
                        String[] args = commandLine.split("\u0000");
                        logf("Compiling %s\n", join(" ", args));

                        int result = compiler.compile(args);
                        logf("Result = %d\n", result);

                        output.write(result + "\n");
                    }
                } finally {
                    // close IO streams, then socket
                    output.close();
                    input.close();
                    connectionSocket.close();
                }
            } catch (Exception ioe) {
                ioe.printStackTrace();
            }
        }
    }
}
