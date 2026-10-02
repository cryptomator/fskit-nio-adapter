import org.cryptomator.frontend.fskit.mount.FSKitMountProvider;
import org.cryptomator.integrations.mount.MountService;

module org.cryptomator.frontend.fskit {
	requires org.cryptomator.integrations.api;
	requires org.slf4j;
	requires static org.jetbrains.annotations;

	provides MountService with FSKitMountProvider;
}
