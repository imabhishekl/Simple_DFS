package dfs_CL;

import dfs_api.*;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

/**
 * Implements Functions to Support Client-side operations => Create file, getServerAddress, Ping Master node, Send/Receive File, etc.
 * Created by abhishek on 4/24/16.
 */
public class ClientAPI
{
    public static boolean create_file(String file_path,String data)
    {
        if(!FileTransfer.check_and_create_dir(DFS_Globals.sdfs_path))
        {
            System.out.println("Failed in Creating");
            return false;
        }
        try(PrintWriter out = new PrintWriter(new FileWriter(new File(file_path),false)))
        {
            out.print(data);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * In this part of the code we have configured to get the server address THAT WORKS.
       If Primary Master Node Fails, we contact the Secondary Master Node after 2 tries.
     * @return
     */
    public static String getServerAddress()
    {
        //Checking TWICE if Primary Master Node is working
        for (int i = 0; i < DFS_CONSTANTS.TWO;   i++)
        {
            String server_address = (DFS_Globals.server_addr != null)? DFS_Globals.server_addr : System.getenv(DFS_CONSTANTS.DFS_SERVER_ADDR) ;

            if (server_address != null)
            {
                System.out.println("SIP : " + server_address);
                if(!check_mn_service(server_address,DFS_CONSTANTS.ALIVE_LISTEN_PORT))  /* Check if its reachable */
                    continue;
                return server_address;
            }
        }

        //Main Master Node Unreachable. Swap Addresses IF Secondary Master Node was Registered with Client
        DFS_Globals.sec_mn_ip_addr = read_file(DFS_Globals.sdfs_path + DFS_CONSTANTS.sec_nm_data_file);

        if (DFS_Globals.sec_mn_ip_addr == null)
        {
            System.out.println("Sorry No Back up node set : ");
            return null;
        }

        String temp = DFS_Globals.server_addr;
        DFS_Globals.server_addr = DFS_Globals.sec_mn_ip_addr;
        DFS_Globals.sec_mn_ip_addr = "";

        System.out.println ("New Secondary Address: "+ DFS_Globals.server_addr);

        //Checking TWICE if Secondary Master Node is working
        for (int i = 0; i < DFS_CONSTANTS.TWO;   i++)
        {
            String server_address = DFS_Globals.server_addr;

            if (server_address != null)
            {
                if(!check_mn_service(server_address,DFS_CONSTANTS.ALIVE_LISTEN_PORT))  /* Check if its reachable */
                    continue;
                return server_address;
            }
        }

        //BOTH Nodes Unreachable
        System.out.println("No Master Node is Reachable!!!");
        return null;
    }

    /**
     * Checks by Pinging if A Server is Alive
     * @param host
     * @param port
     * @return
     */
    public static boolean check_mn_service(String host,int port)
    {
        try
        {
            Socket server_connect = new Socket();
            server_connect.connect(new InetSocketAddress(host, port), DFS_CONSTANTS.MASTER_PING_TIMEOUT);
            server_connect.close();
            return true; /* Service is up and running */
        }
        catch (IOException ex)
        {
            System.out.println("Timeouttttttt");
            return false; /* Unnable to connect specific port; */
        }
    }

    /**
     * Sends the Client Request => LS, MKDIR, PUT, GET
     * @param client_socket
     * @param req_packet
     */
    public static void send_request(Socket client_socket, ClientRequestPacket req_packet)
    {
        ObjectOutputStream oos = null;
        try
        {
            oos = new ObjectOutputStream(client_socket.getOutputStream());
            oos.writeObject(req_packet);
            oos.flush();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Receives the Output from the Earlier Request
     * @param client_socket
     * @return
     */
    public static ClientResponsePacket recv_response(Socket client_socket)
    {
        ObjectInputStream ois = null;
        ClientResponsePacket res_packet = null;
        try
        {
            /* Wait for the response packet from the server */
            ois = new ObjectInputStream(client_socket.getInputStream());
            res_packet = (ClientResponsePacket) ois.readObject();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            return res_packet;
        }
    }

    /**
     * Gets "Username" AND "Working Master Node Address" from the Stored File on Disk
     * @param file_path
     * @return
     */
    public static String read_file(String file_path)
    {
        /* Check if the file exists or not */
        Scanner cin = null;
        File username = new File(file_path);
        if(username.exists())
        {
            try {
                cin = new Scanner(username);
                return cin.next();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            finally {
                cin.close();
            }
        }
        return null;
    }

    /**
     * Creates a request Packet to send with the given command
     * @param command
     * @return
     */
    public static ClientRequestPacket createRequestPacket(int command)
    {
        ClientRequestPacket req_packet;
        String username = ClientAPI.read_file(DFS_Globals.user_name_file);
        if(username == null)
        {
            System.out.println("Please login.....");
            System.exit(DFS_CONSTANTS.SUCCESS);
        }
        System.out.println("UN : " + username);
        req_packet = new ClientRequestPacket();
        req_packet.client_uuid = username;
        req_packet.command = command;
        return req_packet;
    }

    /**
     * This program connects to the Data Node (received from Master Node) and transfers file to the local system. IF in any case the Data Node is down, Client does not try to request from Other Data Nodes.
     * @param res_packet
     * @param path
     * @return
     */
    public static boolean getFiles(ClientResponsePacket res_packet,String path)
    {
        ClientRequestPacket req_packet = new ClientRequestPacket();
        ClientResponsePacket dn_res_packet = new ClientResponsePacket();

        /* USES Only the 1st element of Data Node List. IF THIS FAILS => Does not handle.
        It should request Master Node again for the file. It will be handled  */
        String dn_ip = res_packet.dn_list.get(DFS_CONSTANTS.ZERO).IPAddr;
        Socket connect = null;
        FileTransfer ftp = new FileTransfer();

        req_packet.command = DFS_CONSTANTS.GET;
        req_packet.client_uuid = ClientAPI.read_file(DFS_Globals.user_name_file);
        req_packet.file_name = res_packet.file_name;
        req_packet.file_size = res_packet.file_size;
        req_packet.dn_list = res_packet.dn_list;
        System.out.println("Path:" + req_packet.file_name + ":" + res_packet.file_size);
        try
        {
            connect = new Socket(dn_ip,DFS_CONSTANTS.DN_LISTEN_PORT);
            send_request(connect,req_packet);
            dn_res_packet = ClientAPI.recv_response(connect);

            if (dn_res_packet !=null && dn_res_packet.response_code == DFS_CONSTANTS.OK)
            {
                /* Start Sending the file */
                ftp.save_file(connect,System.getProperty("user.dir") + "/" + req_packet.file_name,req_packet.file_size);
            }
            else
            {
                return false;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return false;
        }
        finally {
            try {
                connect.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }


    /**
     * This program connects to the Data Node and transfers it.
     * @param res_packet
     * @param path
     * @return
     */
    public static boolean sendFiles(ClientResponsePacket res_packet,String path)
    {
        ClientRequestPacket req_packet = new ClientRequestPacket();
        ClientResponsePacket dn_res_packet = new ClientResponsePacket();
        String dn_ip = res_packet.dn_list.get(DFS_CONSTANTS.ZERO).IPAddr;
        Socket connect = null;
        FileTransfer ftp = new FileTransfer();

        /* Creating request packet for DN */
        req_packet.command = DFS_CONSTANTS.PUT;
        req_packet.client_uuid = ClientAPI.read_file(DFS_Globals.user_name_file);
        req_packet.file_name = res_packet.file_name;
        req_packet.file_size = res_packet.file_size;
        req_packet.arguments = res_packet.arguments;
        req_packet.dn_list = res_packet.dn_list;
        req_packet.replicate_ind = res_packet.replicate_ind;

        System.out.println("File Name : " + req_packet.file_name + ":" + req_packet.dn_list.size());

        try
        {
            System.out.println("DN IP : " + dn_ip);
            connect = new Socket(dn_ip,DFS_CONSTANTS.DN_LISTEN_PORT);
            send_request(connect,req_packet);
            System.out.println("In Send File wit dn ip :" + dn_ip);
            dn_res_packet = ClientAPI.recv_response(connect);
            if (dn_res_packet !=null && dn_res_packet.response_code == DFS_CONSTANTS.OK)
            {
                /* Start Sending the file */
                System.out.println("Starting sending file....");
                ftp.send_file(connect,path);
            }
            else
            {
                System.out.println("Recv null from DN response : " + dn_res_packet == null);
                return false;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
            return false;
        }
        finally {
            try {
                connect.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    /**
     * Validates if File pointed by input is valid on the local file system
     * @param file
     * @return
     */
    public static boolean validate_file(String file)
    {
        File f = new File(file);
        System.out.println("validate file : " + file + " IsDir : " + f.exists());
        if(f.exists() && !f.isDirectory()) {
            return true;
        }
        return false;
    }

    public static int getFileSize(String file)
    {
        return (int)(new File(file).length());
    }
}
