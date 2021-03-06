/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

public class MonoUntilOtherTest {

	@Test
	public void testMonoValuedAndPublisherVoid() {
		Publisher<Void> voidPublisher = Mono.fromRunnable(() -> { });
		StepVerifier.create(new MonoUntilOther<>(false, Mono.just("foo"), voidPublisher))
		            .expectNext("foo")
		            .verifyComplete();
	}

	@Test
	public void testMonoEmptyAndPublisherVoid() {
		Publisher<Void> voidPublisher = Mono.fromRunnable(() -> { });
		StepVerifier.create(new MonoUntilOther<>(false, Mono.<String>empty(), voidPublisher))
		            .verifyComplete();
	}

	@Test
	public void triggerSequenceWithDelays() {
		Duration duration = StepVerifier.create(new MonoUntilOther<>(false,
				Mono.just("foo"),
				Flux.just(1, 2, 3).hide().delayElements(Duration.ofMillis(500))))
		            .expectNext("foo")
		            .verifyComplete();

		assertThat(duration.toMillis()).isGreaterThanOrEqualTo(500);
	}

	@Test
	public void triggerSequenceHasMultipleValuesCancelled() {
		AtomicBoolean triggerCancelled = new AtomicBoolean();
		StepVerifier.create(new MonoUntilOther<>(false,
				Mono.just("foo"),
				Flux.just(1, 2, 3).hide()
				    .doOnCancel(() -> triggerCancelled.set(true))))
		            .expectNext("foo")
		            .verifyComplete();
		assertThat(triggerCancelled.get()).isTrue();
	}

	@Test
	public void triggerSequenceHasSingleValueNotCancelled() {
		AtomicBoolean triggerCancelled = new AtomicBoolean();
		StepVerifier.create(new MonoUntilOther<>(false,
				Mono.just("foo"),
				Mono.just(1)
				    .doOnCancel(() -> triggerCancelled.set(true))))
		            .expectNext("foo")
		            .verifyComplete();
		assertThat(triggerCancelled.get()).isFalse();
	}

	@Test
	public void triggerSequenceDoneFirst() {
		StepVerifier.withVirtualTime(() -> new MonoUntilOther<>(false,
				Mono.delay(Duration.ofSeconds(2)),
				Mono.just("foo")))
		            .expectSubscription()
		            .expectNoEvent(Duration.ofSeconds(2))
		            .expectNext(0L)
		            .verifyComplete();
	}

	@Test
	public void sourceHasError() {
		StepVerifier.create(new MonoUntilOther<>(false,
				Mono.<String>error(new IllegalStateException("boom")),
				Mono.just("foo")))
		            .verifyErrorMessage("boom");
	}

	@Test
	public void triggerHasError() {
		StepVerifier.create(new MonoUntilOther<>(false,
				Mono.just("foo"),
				Mono.<String>error(new IllegalStateException("boom"))))
		            .verifyErrorMessage("boom");
	}

	@Test
	public void sourceAndTriggerHaveErrorsNotDelayed() {
		StepVerifier.create(new MonoUntilOther<>(false,
				Mono.<String>error(new IllegalStateException("boom1")),
				Mono.<Integer>error(new IllegalStateException("boom2"))))
		            .verifyErrorMessage("boom1");
	}

	@Test
	public void sourceAndTriggerHaveErrorsDelayed() {
		IllegalStateException boom1 = new IllegalStateException("boom1");
		IllegalStateException boom2 = new IllegalStateException("boom2");
		StepVerifier.create(new MonoUntilOther<>(true,
				Mono.<String>error(boom1),
				Mono.<Integer>error(boom2)))
		            .verifyErrorMatches(e -> e.getMessage().equals("Multiple errors") &&
				            e.getSuppressed()[0] == boom1 &&
				            e.getSuppressed()[1] == boom2);
	}

	@Test
	public void testAPIUntilOther() {
		StepVerifier.withVirtualTime(() -> Mono.just("foo")
		                                       .untilOther(Mono.delay(Duration.ofSeconds(2))))
		            .expectSubscription()
		            .expectNoEvent(Duration.ofSeconds(2))
		            .expectNext("foo")
		            .verifyComplete();
	}

	@Test
	public void testAPIUntilOtherErrorsImmediately() {
		IllegalArgumentException boom = new IllegalArgumentException("boom");
		StepVerifier.create(Mono.error(boom)
		                        .untilOther(Mono.delay(Duration.ofSeconds(2))))
		            .expectErrorMessage("boom")
		            .verify(Duration.ofMillis(200)); //at least, less than 2s
	}

	@Test
	public void testAPIUntilOtherDelayErrorNoError() {
		StepVerifier.withVirtualTime(() -> Mono.just("foo")
		                                       .untilOtherDelayError(Mono.delay(Duration.ofSeconds(2))))
		            .expectSubscription()
		            .expectNoEvent(Duration.ofSeconds(2))
		            .expectNext("foo")
		            .verifyComplete();
	}

	@Test
	public void testAPIUntilOtherDelayErrorWaitsOtherTriggers() {
		IllegalArgumentException boom = new IllegalArgumentException("boom");

		StepVerifier.withVirtualTime(() -> Mono.error(boom)
		                                       .untilOtherDelayError(Mono.delay(Duration.ofSeconds(2))))
		            .expectSubscription()
		            .expectNoEvent(Duration.ofSeconds(2))
		            .verifyErrorMessage("boom");
	}

	@Test
	public void testAPIchainingCombines() {
		Mono<String> source = Mono.just("foo");

		Mono<String> until1 = source.untilOther(
				Flux.just(1, 2, 3));

		Mono<String> until2 = until1.untilOther(
				Mono.delay(Duration.ofMillis(800)));

		assertThat(until1).isSameAs(until2);

		StepVerifier.create(until2)
		            .expectSubscription()
		            .expectNoEvent(Duration.ofMillis(700))
		            .thenAwait(Duration.ofMillis(100))
		            .expectNext("foo")
		            .verifyComplete();
	}

	@Test
	public void testAPIchainingCombinesWithFirstDelayErrorParameter() {
		Mono<String> source = Mono.just("foo");

		Mono<String> until1 = source.untilOtherDelayError(
				Mono.error(new IllegalArgumentException("boom")));

		Mono<String> until2 = until1.untilOther(
				Mono.delay(Duration.ofMillis(800)));

		assertThat(until1).isSameAs(until2);

		StepVerifier.create(until2)
		            .expectSubscription()
		            .expectNoEvent(Duration.ofMillis(700))
		            .thenAwait(Duration.ofMillis(100))
		            .verifyErrorMessage("boom");
	}
}