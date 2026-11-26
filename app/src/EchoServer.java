import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor de eco TCP, como los de las prácticas de la asignatura.
 * Responde a cada línea indicando qué réplica (pod) y qué versión la ha atendido,
 * para que se vea el balanceo y las actualizaciones en caliente.
 */
public class EchoServer {

    // Cambia este valor y haz push para ver el despliegue automático (rolling update).
    static final String VERSION = "v1";

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "5000"));
        // Kubernetes pone el nombre del pod en la variable HOSTNAME.
        String pod = System.getenv().getOrDefault("HOSTNAME", "local");
        ExecutorService pool = Executors.newFixedThreadPool(16);

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf("[%s] EchoServer %s escuchando en el puerto %d%n", pod, VERSION, port);
            while (true) {
                Socket socket = server.accept();
                pool.submit(() -> atender(socket, pod));
            }
        }
    }

    private static void atender(Socket socket, String pod) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            String linea;
            while ((linea = in.readLine()) != null) {
                out.printf("[%s | %s] %s%n", pod, VERSION, linea);
            }
        } catch (IOException e) {
            System.err.printf("[%s] Conexión cerrada: %s%n", pod, e.getMessage());
        }
    }
}
