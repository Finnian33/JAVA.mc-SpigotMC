          synchronized (optOutLock) {
	                           // Disable Task, if it is running and the server owner decided to opt-out
	                           if (isOptOut() && task != null) {
	                               task.cancel();
	                               task = null;
	                               // Tell all plotters to stop gathering information.
	                               for (Graph graph : graphs) {
	                                   graph.onOptOut();
	                               }
	                           }
	                       }

	                       // We use the inverse of firstPost because if it is the first time we are posting,
	                       // it is not a interval ping, so it evaluates to FALSE
	                       // Each time thereafter it will evaluate to TRUE, i.e PING!
	                       postPlugin(!firstPost);

	                       // After the first post we set firstPost to false
	                       // Each post thereafter will be a ping
	                       firstPost = false;
	                   } catch (IOException e) {
	                       if (debug) {
	                           Bukkit.getLogger().log(Level.INFO, "[Metrics] " + e.getMessage());
	                       }
	                   }
	               }
	           }, 0, TimeUnit.MINUTES.toMillis(PING_INTERVAL));

	           return true;
	       }
	   }

	   /**
	    * Has the server owner denied plugin metrics?
	    *
	    * @return true if metrics should be opted out of it
	    */
	   public boolean isOptOut() {
	       synchronized (optOutLock) {
	           try {
	               // Reload the metrics file
	               configuration.load(getConfigFile());
	           } catch (IOException ex) {
	               if (debug) {
	                   Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
	               }
	               return true;
	           } catch (InvalidConfigurationException ex) {
	               if (debug) {
	                   Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
	               }
	               return true;
	           }
	           return configuration.getBoolean("opt-out", false);
	       }
	   }

	   /**
	    * Enables metrics for the server by setting "opt-out" to false in the config file and starting the metrics task.
	    *
	    * @throws java.io.IOException
	    */
	   public void enable() throws IOException {
	       // This has to be synchronized or it can collide with the check in the task.
	       synchronized (optOutLock) {
	           // Check if the server owner has already set opt-out, if not, set it.
	           if (isOptOut()) {
	               configuration.set("opt-out", false);
	               configuration.save(configurationFile);
	           }

	           // Enable Task, if it is not running
	           if (task == null) {
	               start();
	           }
	       }
	   }

	   /**
	    * Disables metrics for the server by setting "opt-out" to true in the config file and canceling the metrics task.
	    *
	    * @throws java.io.IOException
	    */
	   public void disable() throws IOException {
	       // This has to be synchronized or it can collide with the check in the task.
	       synchronized (optOutLock) {
	           // Check if the server owner has already set opt-out, if not, set it.
	           if (!isOptOut()) {
	               configuration.set("opt-out", true);
	               configuration.save(configurationFile);
	           }

	           // Disable Task, if it is running
	           if (task != null) {
	               task.cancel();
	               task = null;
	           }
	       }
	   }

	   /**
	    * Gets the File object of the config file that should be used to store data such as the GUID and opt-out status
	    *
	    * @return the File object for the config file
	    */
	   public File getConfigFile() {
	       // I believe the easiest way to get the base folder (e.g craftbukkit set via -P) for plugins to use
	       // is to abuse the plugin object we already have
	       // plugin.getDataFolder() => base/plugins/PluginA/
	       // pluginsFolder => base/plugins/
	       // The base is not necessarily relative to the startup directory.
	       // File pluginsFolder = plugin.getDataFolder().getParentFile();

	       // return => base/plugins/PluginMetrics/config.yml
	       return new File(new File((File) MinecraftServer.getServer().options.valueOf("plugins"), "PluginMetrics"), "config.yml");
	   }

	   /**
	    * Generic method that posts a plugin to the metrics website
	    */
	   private void postPlugin(final boolean isPing) throws IOException {
	       // Server software specific section
	       String pluginName = "Spigot";
	       boolean onlineMode = Bukkit.getServer().getOnlineMode(); // TRUE if online mode is enabled
	       String pluginVersion = (Metrics.class.getPackage().getImplementationVersion() != null) ? Metrics.class.getPackage().getImplementationVersion() : "unknown";
	       String serverVersion = Bukkit.getVersion();
	       int playersOnline = Bukkit.getServer().getOnlinePlayers().size();

	       // END server software specific section -- all code below does not use any code outside of this class / Java

	       // Construct the post data
	       final StringBuilder data = new StringBuilder();

	       // The plugin's description file containg all of the plugin data such as name, version, author, etc
	       data.append(encode("guid")).append('=').append(encode(guid));
	       encodeDataPair(data, "version", pluginVersion);
	       encodeDataPair(data, "server", serverVersion);
	       encodeDataPair(data, "players", Integer.toString(playersOnline));
	       encodeDataPair(data, "revision", String.valueOf(REVISION));

	       // New data as of R6
	       String osname = System.getProperty("os.name");
	       String osarch = System.getProperty("os.arch");
	       String osversion = System.getProperty("os.version");
	       String java_version = System.getProperty("java.version");
	       int coreCount = Runtime.getRuntime().availableProcessors();

	       // normalize os arch .. amd64 -> x86_64
	       if (osarch.equals("amd64")) {
	           osarch = "x86_64";
	       }

	       encodeDataPair(data, "osname", osname);
	       encodeDataPair(data, "osarch", osarch);
	       encodeDataPair(data, "osversion", osversion);
	       encodeDataPair(data, "cores", Integer.toString(coreCount));
	       encodeDataPair(data, "online-mode", Boolean.toString(onlineMode));
	       encodeDataPair(data, "java_version", java_version);

	       // If we're pinging, append it
	       if (isPing) {
	           encodeDataPair(data, "ping", "true");
	       }

	       // Acquire a lock on the graphs, which lets us make the assumption we also lock everything
	       // inside of the graph (e.g plotters)
	       synchronized (graphs) {
	           final Iterator<Graph> iter = graphs.iterator();

	           while (iter.hasNext()) {
	               final Graph graph = iter.next();

	               for (Plotter plotter : graph.getPlotters()) {
	                   // The key name to send to the metrics server
	                   // The format is C-GRAPHNAME-PLOTTERNAME where separator - is defined at the top
	                   // Legacy (R4) submitters use the format Custom%s, or CustomPLOTTERNAME
	                   final String key = String.format("C%s%s%s%s", CUSTOM_DATA_SEPARATOR, graph.getName(), CUSTOM_DATA_SEPARATOR, plotter.getColumnName());

	                   // The value to send, which for the foreseeable future is just the string
	                   // value of plotter.getValue()
	                   final String value = Integer.toString(plotter.getValue());

	                   // Add it to the http post data :)
	                   encodeDataPair(data, key, value);
	               }
	           }
	       }

	       // Create the url
	       URL url = new URL(BASE_URL + String.format(REPORT_URL, encode(pluginName)));

	       // Connect to the website
	       URLConnection connection;

	       // Mineshafter creates a socks proxy, so we can safely bypass it
	       // It does not reroute POST requests so we need to go around it
	       if (isMineshafterPresent()) {
	           connection = url.openConnection(Proxy.NO_PROXY);
	       } else {
	           connection = url.openConnection();
	       }

	       connection.setDoOutput(true);

	       // Write the data
	       final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
	       writer.write(data.toString());
	       writer.flush();

	       // Now read the response
	       final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
	       final String response = reader.readLine();

	       // close resources
	       writer.close();
	       reader.close();

	       if (response == null || response.startsWith("ERR")) {
	           throw new IOException(response); //Throw the exception
	       } else {
	           // Is this the first update this hour?
	           if (response.contains("OK This is your first update this hour")) {
	               synchronized (graphs) {
	                   final Iterator<Graph> iter = graphs.iterator();

	                   while (iter.hasNext()) {
	                       final Graph graph = iter.next();

	                       for (Plotter plotter : graph.getPlotters()) {
	                           plotter.reset();
	                       }
	                   }
	               }
	           }
	       }
	   }

	   /**
	    * Check if mineshafter is present. If it is, we need to bypass it to send POST requests
	    *
	    * @return true if mineshafter is installed on the server
	    */
	   private boolean isMineshafterPresent() {
	       try {
