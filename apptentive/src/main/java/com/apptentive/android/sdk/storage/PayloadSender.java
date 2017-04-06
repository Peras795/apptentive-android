/*
 * Copyright (c) 2017, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.storage;

import com.apptentive.android.sdk.ApptentiveLog;
import com.apptentive.android.sdk.model.Payload;
import com.apptentive.android.sdk.network.HttpJsonRequest;
import com.apptentive.android.sdk.network.HttpRequest;
import com.apptentive.android.sdk.network.HttpRequestRetryPolicy;
import com.apptentive.android.sdk.util.StringUtils;

import static com.apptentive.android.sdk.ApptentiveLogTag.PAYLOADS;

class PayloadSender {
	private final PayloadRequestSender requestSender;
	private final HttpRequestRetryPolicy requestRetryPolicy;

	private Listener listener;
	private boolean sendingFlag; // this variable is only accessed in a synchronized context

	PayloadSender(PayloadRequestSender requestSender, HttpRequestRetryPolicy retryPolicy) {
		if (requestSender == null) {
			throw new IllegalArgumentException("Payload request sender is null");
		}

		if (retryPolicy == null) {
			throw new IllegalArgumentException("Retry policy is null");
		}

		this.requestSender = requestSender;
		this.requestRetryPolicy = retryPolicy;
	}

	//region Payloads

	synchronized boolean sendPayload(final Payload payload) {
		if (payload == null) {
			throw new IllegalArgumentException("Payload is null");
		}

		// we don't allow concurrent payload sending
		if (isSendingPayload()) {
			return false;
		}

		sendingFlag = true;

		try {
			sendPayloadRequest(payload);
		} catch (Exception e) {
			ApptentiveLog.e(PAYLOADS, "Exception while sending payload: %s", payload);

			String message = e.getMessage();
			if (message == null) {
				message = StringUtils.format("%s is thrown", e.getClass().getSimpleName());
			}

			handleFinishSendingPayload(payload, false, message);
		}

		return true;
	}

	private synchronized void sendPayloadRequest(final Payload payload) {
		ApptentiveLog.d(PAYLOADS, "Sending payload: %s:%d (%s)", payload.getBaseType(), payload.getDatabaseId(), payload.getConversationId());

		final HttpRequest payloadRequest = requestSender.sendPayload(payload, new HttpRequest.Listener<HttpRequest>() {
			@Override
			public void onFinish(HttpRequest request) {
				handleFinishSendingPayload(payload, false, null);
			}

			@Override
			public void onCancel(HttpRequest request) {
				handleFinishSendingPayload(payload, true, null);
			}

			@Override
			public void onFail(HttpRequest request, String reason) {
				handleFinishSendingPayload(payload, false, reason);
			}
		});
		payloadRequest.setRetryPolicy(requestRetryPolicy);
	}

	//endregion

	//region Listener notification

	private synchronized void handleFinishSendingPayload(Payload payload, boolean cancelled, String errorMessage) {
		sendingFlag = false;

		try {
			if (listener != null) {
				listener.onFinishSending(this, payload, cancelled, errorMessage);
			}
		} catch (Exception e) {
			ApptentiveLog.e(e, "Exception while notifying payload listener");
		}
	}

	//endregion

	//region Getters/Setters

	synchronized boolean isSendingPayload() {
		return sendingFlag;
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	//endregion

	//region Listener

	public interface Listener {
		void onFinishSending(PayloadSender sender, Payload payload, boolean cancelled, String errorMessage);
	}

	//endregion
}
