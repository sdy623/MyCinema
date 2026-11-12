package local.cinemamanager.seats.fractional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
public final class FractionalSeatResourceRefusalChecks {
 public static void main(String[] args) {
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  try { FractionalSeatLayout.get(FractionalSeatLayoutVersion.Version.valueOf(args[0])); }
  catch (RuntimeException expected) { System.out.println("PASS refuses tampered resource: "+expected.getMessage());return; }
  throw new AssertionError("Tampered resource unexpectedly accepted");
 }
}
