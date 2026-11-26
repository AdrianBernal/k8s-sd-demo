import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Cliente que abre UNA CONEXIÓN NUEVA por petición, cada medio segundo.
 * Así se ve que el Service balancea por conexión TCP (no por mensaje)
 * y que, si un pod muere, las siguientes conexiones van a otra réplica.
 *
 * Uso: java EchoClient <host> <puerto>   (por defecto: echo 5000)
 */
public class EchoClient {

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "echo";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        DateTimeFormatter hora = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        for (long n = 1; ; n++) {
            String ts = LocalTime.now().format(hora);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 1000);
                s.setSoTimeout(1000);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                out.println("ping " + n);
                System.out.println(ts + "  " + in.readLine());
            } catch (Exception e) {
                System.out.println(ts + "  ERROR: " + e.getMessage());
            }
            Thread.sleep(500);
        }
    }
}
