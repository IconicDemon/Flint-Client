package com.flintclient.bench;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Records every garbage collection the JVM reports into a {@link GcRing}. Notifications arrive on a JMX
 * thread after the collection ends; the handler touches nothing but the ring.
 *
 * <p>Bean names tell pauses from concurrent work: "ZGC Minor/Major Pauses" are pauses and "... Cycles"
 * concurrent cycles; "G1 Young Generation" collections stop the world, and "G1 Concurrent GC" reports the
 * Remark and Cleanup pauses of concurrent cycles. GcInfo durations have millisecond resolution, so short pauses
 * show as 0 here; the {@code -Xlog:gc} file has the precise pause times.
 */
final class GcListener {
	private GcListener() {
	}

	/** Subscribes to every collector bean; returns the bean names (also recorded in the result). */
	static List<String> install(GcRing ring) {
		List<String> names = new ArrayList<>();
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			names.add(bean.getName());
			if (!(bean instanceof NotificationEmitter emitter)) {
				continue;
			}
			try {
				emitter.addNotificationListener((notification, handback) -> {
					if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
						return;
					}
					long now = System.nanoTime();
					GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
					GcInfo gc = info.getGcInfo();
					ring.add(now, gc.getStartTime(), info.getGcName(), info.getGcAction(), info.getGcCause(), gc.getDuration());
				}, null, null);
			} catch (Throwable t) {
				// jdk.management missing or a bean that refuses listeners: run without GC events.
				FlintBench.LOGGER.warn("[flintbench] can't listen to {} notifications", bean.getName(), t);
			}
		}
		return names;
	}
}
