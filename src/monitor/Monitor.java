	package monitor;
	
	import java.io.BufferedReader;
	import java.io.IOException;
	import java.io.InputStreamReader;
	import java.net.InetAddress;
	import java.net.UnknownHostException;
	import java.sql.Connection;
	import java.sql.DriverManager;
	import java.sql.ResultSet;
	import java.sql.SQLException;
	import java.sql.Statement;
	import java.util.ArrayList;
	import java.util.Properties;
	import java.util.regex.Matcher;
	import java.util.regex.Pattern;
	import javax.mail.Message;
	import javax.mail.MessagingException;
	import javax.mail.PasswordAuthentication;
	import javax.mail.Session;
	import javax.mail.Transport;
	import javax.mail.internet.InternetAddress;
	import javax.mail.internet.MimeMessage;
	
	public class Monitor 
	{
		private String cmd;
		private ArrayList<String> sqlInstances;
		private String allServices;
		private String username ="";
		private String password ="";
		private String from = "";
		private String to = "";
		private String mailMessage = "";
		private String subject = "Checks failed on host ";
		private ArrayList<Integer> memoryHistory;
		private int memoryAbsoluteThreshold;
		private double memoryRelativeThreshold;
		private boolean createAlert;
		
		public static void main(String[] args) throws IOException 
		{
			@SuppressWarnings("unused")
			Monitor t = new Monitor();
		}
		public Monitor() throws UnknownHostException 
		{
			memoryAbsoluteThreshold = 2048;
			memoryRelativeThreshold = 0.95D;
			memoryHistory = new ArrayList<>();
			subject = subject+ getHostName();
			cmd = "sc queryex type= service state= all";
			createAlert = false;
			try {
				allServices = getServices(cmd);
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
			sqlInstances = getInstances(allServices);
			checkSqlServer();
			checkMemory();	
			if(createAlert)
			{
			//sendMail();
			}
		}
		public void checkMemory() throws UnknownHostException
		{
		        
			 int mb = 1024*1024;	
			 Runtime runtime = Runtime.getRuntime();
			 int freeMemory = (int) (runtime.freeMemory() / mb);
			 int totalMemory = (int) (runtime.totalMemory() / mb);
			 int freeMemoryRatio = freeMemory / totalMemory;
			 memoryHistory.add(freeMemoryRatio);
			 
			 if(memoryHistory.size() == 120)
			 	{
				 if(isMemoryBreached())
				 {
					 mailMessage = mailMessage+ "\n" + "Heap memory on server " + getHostName() + " has breached the " + memoryRelativeThreshold*100 + "% threshold";
					 createAlert = true;
				 }
				 memoryHistory.clear();
			 }
		 
		 	   
		}
		public boolean isMemoryBreached()
		{
			int counter = 0;
			double result;
			for(Integer record:memoryHistory)
			{
				if(record > memoryRelativeThreshold)
				{
				counter ++;
				}
			}
			result = counter / memoryHistory.size();
			if(result > memoryRelativeThreshold)
			{
				return true;
			}
			else 
			{
				return false;
			}
		}
		
		public void checkSqlServer()
		{
			String currentInstance ="";
			for(String instance:sqlInstances)
			{
				currentInstance= instance;
				try 
				{
					checkDB(currentInstance);
				}
				catch (SQLException e) 
				{
					mailMessage = mailMessage+ "\n" + "Check for "+ currentInstance+ " Unsucessfull";
					createAlert = true;
				}	
			}	
		}
		
		public void sendMail()
		{
			 	Properties props = new Properties();
			 	props.put("mail.smtp.auth", "true");
			 	props.put("mail.smtp.starttls.enable", "true");
			 	props.put("mail.smtp.host", "smtp.gmail.com");
			 	props.put("mail.smtp.port", "587");
			 	Session session = Session.getInstance(props,
			 	new javax.mail.Authenticator() {
			 	protected PasswordAuthentication getPasswordAuthentication() {
			 	return new PasswordAuthentication(username, password);
			 	}
			 	});
		 	
			 	try 
			 	{
				 	Message message = new MimeMessage(session);
				 	message.setFrom(new InternetAddress(from));
				 	message.setRecipients(Message.RecipientType.TO,InternetAddress.parse(to));
				 	message.setSubject(subject);
				 	message.setText(mailMessage);
				 	Transport.send(message);
			 	} 
			 	catch (MessagingException e)
			 	{
			 		throw new RuntimeException(e);
			 	}
		 }
		 	
		public String getServices(String cmd) throws IOException
		{
				String line = null;
				Process proc = null;
				proc = Runtime.getRuntime().exec(cmd);          
		        InputStreamReader stream = new InputStreamReader(proc.getInputStream());
		        BufferedReader br = new BufferedReader(stream);
		        StringBuilder string = new StringBuilder();
		               
		        while (( line = br.readLine() ) != null)
		        {
		        	string.append(line);
		        }
		        String result = string.toString();
		        return result;
		}
		
		public ArrayList<String> getInstances(String string)
		{
			ArrayList<String> sqlInstances = new ArrayList<>();
			ArrayList<String> temp = new ArrayList<>();
			Pattern pattern = Pattern.compile("MSSQL\\$.+\\b|MSSQLSERVER", Pattern.CASE_INSENSITIVE);
			Matcher findInstance = pattern.matcher(string);
			String instance;
			   
			   while (findInstance.find()) 
			   {
			     int start = findInstance.start();
			     int end = findInstance.end();
			     instance = string.substring(start,end);
			     temp.add(instance.toLowerCase());
			   }
			   String host="";
			   
			try
			{
				host = getHostName();
			} catch (UnknownHostException e) {
				System.exit(1);
			}
			   
			for(String name:temp)
			{
				String current;
			   	if(name.trim().equalsIgnoreCase("mssqlserver"))
			   	{
			   		current = host;
			   	}
			   	else
			   	{
			   		current = host+"\\"+name.trim();
			   	}
				if(!sqlInstances.contains(current))
				{
					sqlInstances.add(current);
				}
			}
			   
			return sqlInstances;
		}
		
		public String getHostName() throws UnknownHostException
		{
			String hostname="";
			InetAddress addr;
		    addr = InetAddress.getLocalHost();
		    hostname = addr.getHostName();
		    return hostname;
		}
		
		public void checkDB(String instance) throws SQLException, NullPointerException 
		{
			boolean isCheckOk = true;
			ArrayList<String> dbNames = new ArrayList<>();	
			String source = "jdbc:sqlserver://"+instance+ ";integratedSecurity=true;";
			Connection conn = null;
			String Sql;
			ResultSet rs = null;
			Statement sta = null;
			
			try 
			{
				conn = connectToInstance(source);
				sta = conn.createStatement();
				Sql = "select * from master.sys.databases";
				rs = sta.executeQuery(Sql);
			} 
			catch (SQLException | ClassNotFoundException e1) 
			{
				mailMessage = "Unable to connect to instance "+instance;
				throw new SQLException();
			}
			
			Sql = "";
			String name = null;
			try
			{
				while (rs.next()) 
				{
					name = rs.getString("name");
					dbNames.add(name);
				}
			} 
			catch (SQLException e) 
			{
				isCheckOk = false;
			} 
			catch(NullPointerException e1)
			{
				mailMessage = "Error connecting to instance " + instance;
				throw new SQLException();
			}
			
			for(String db:dbNames)
			{
				try 
				{
					Sql = "USE "+ db+";"+ " select 'Test'";
					query(sta, Sql);
					System.out.println("Connection to "+ db+ " successfull");
				}
				catch (SQLException e)
				{
					mailMessage = "Unable to connect to database " + db + " on instance "+ instance + "\n" + mailMessage;
					isCheckOk = false;
				}
			}
			
			try 
			{
				conn.close();
				rs.close();
			} 
			catch (SQLException e) 
			{
				isCheckOk = false;
			}
			
			if(isCheckOk == false)
			{
				subject = subject + " " + instance;
				throw new SQLException();
			}
		}
		
		public void query(Statement sta, String query) throws SQLException
		{
			ResultSet set = sta.executeQuery(query);
			set.close();
		}
		public Connection connectToInstance(String source) throws ClassNotFoundException, SQLException
		{
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			Connection conn = DriverManager.getConnection(source);
			return conn;
		}
	}