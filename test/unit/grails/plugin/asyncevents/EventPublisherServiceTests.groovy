package grails.plugin.asyncevents

import grails.test.MockUtils
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.springframework.context.ApplicationEvent

import static java.util.concurrent.TimeUnit.SECONDS
import static org.hamcrest.CoreMatchers.not
import static org.hamcrest.CoreMatchers.sameInstance
import static org.junit.Assert.*

class EventPublisherServiceTests {

	EventPublisherService service = new EventPublisherService()

	@Test
	void shutsDownCleanly() {
		service.destroy()

		assertTrue "Worker thread has not stopped", service.executor.awaitTermination(1, SECONDS)
	}

	@Test
	void publishesEventToAllListeners() {
		int numListeners = 2
		def latch = new CountDownLatch(numListeners)
		def event = new MockEvent()

		numListeners.times {
			service.addListener new MockListener(latch)
		}

		service.publishEvent(event)

		waitForAllListenersToBeNotified(latch)
	}

	@Test
	void notifiesListenersOnASeparateThread() {
		def latch = new CountDownLatch(1)
		def event = new MockEvent()
		def listener = new ThreadRecordingListener(latch)

		service.addListener listener

		service.publishEvent(event)

		waitForAllListenersToBeNotified(latch)

		assertThat "thread used for listener notification", listener.thread, not(sameInstance(Thread.currentThread()))
	}

	@Test
	void survivesListenerException() {
		def latch = new CountDownLatch(2)
		def event = new MockEvent()

		service.addListener new ExceptionThrowingListener(latch)
		service.addListener new MockListener(latch)

		service.publishEvent(event)

		waitForAllListenersToBeNotified(latch)
	}

	@Test
	void retriesAfterDelayIfListenerReturnsFalse() {
		def latch = new CountDownLatch(2)
		def event = new MockEvent()

		service.addListener new FailingListener(latch, 1)

		service.publishEvent(event)

		waitForAllListenersToBeNotified(latch)
	}

	private void waitForAllListenersToBeNotified(CountDownLatch latch) {
		if (!latch.await(5, SECONDS)) {
			fail "Timeout out waiting for event notifications. Still expecting $latch.count notifications"
		}
	}

	@BeforeClass
	static void enableLogging() {
		MockUtils.mockLogging EventPublisherService, true
	}

	@Before
	void initialiseService() {
		service.afterPropertiesSet()
	}

	@After
	void stopExecutors() {
		service.destroy()
	}

}

class MockEvent extends ApplicationEvent {
	static final DUMMY_EVENT_SOURCE = new Object()

	MockEvent() {
		super(DUMMY_EVENT_SOURCE)
	}
}

class MockListener implements AsyncEventListener {

	private final CountDownLatch latch

	MockListener(CountDownLatch latch) {
		this.latch = latch
	}

	boolean onApplicationEvent(ApplicationEvent e) {
		latch.countDown()
		return true
	}

	long getRetryDelay() {
		return SECONDS.toMillis(1)
	}
}

class ThreadRecordingListener extends MockListener {

	Thread thread

	ThreadRecordingListener(CountDownLatch latch) {
		super(latch)
	}

	boolean onApplicationEvent(ApplicationEvent e) {
		super.onApplicationEvent(e)
		thread = Thread.currentThread()
		return true
	}
}

class ExceptionThrowingListener extends MockListener {

	private final Exception exception

	def ExceptionThrowingListener(CountDownLatch latch) {
		this(latch, new RuntimeException("Event listener fail"))
	}

	ExceptionThrowingListener(CountDownLatch latch, Exception exception) {
		super(latch)
		this.exception = exception
	}

	boolean onApplicationEvent(ApplicationEvent e) {
		super.onApplicationEvent(e)
		throw exception
	}
}

class FailingListener extends MockListener {

	private int failThisManyTimes = 1

	FailingListener(CountDownLatch latch, int failThisManyTimes) {
		super(latch)
		this.failThisManyTimes = failThisManyTimes
	}

	boolean onApplicationEvent(ApplicationEvent e) {
		super.onApplicationEvent(e)
		return !shouldFail()
	}

	boolean shouldFail() {
		def fail = failThisManyTimes > 0
		failThisManyTimes--
		return fail
	}
}