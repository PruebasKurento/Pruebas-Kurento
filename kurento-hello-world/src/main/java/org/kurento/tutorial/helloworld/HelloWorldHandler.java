/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package org.kurento.tutorial.helloworld;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Hello World handler (application and media logic).
 * 
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author David Fernandez (d.fernandezlop@gmail.com)
 * @since 6.0.0
 */
public class HelloWorldHandler extends TextWebSocketHandler {

	private final Logger log = LoggerFactory.getLogger(HelloWorldHandler.class);
	private static final Gson gson = new GsonBuilder().create();

	@Autowired
	private KurentoClient kurento;

	private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<String, UserSession>();

	@Override
	public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

		log.debug("Incoming message: {}", jsonMessage);

		switch (jsonMessage.get("id").getAsString()) {
		case "start":
			start(session, jsonMessage);
			break;
		case "stop": {
			UserSession user = users.remove(session.getId());
			if (user != null) {
				user.release();
			}
			break;
		}
		case "onIceCandidate": {
			JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();

			UserSession user = users.get(session.getId());
			if (user != null) {
				IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
						jsonCandidate.get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
				user.addCandidate(candidate);
			}
			break;
		}
		default:
			sendError(session, "Invalid message with id " + jsonMessage.get("id").getAsString());
			break;
		}
	}

	private void start(final WebSocketSession session, JsonObject jsonMessage) {
		try {
			// 1. Media logic (webRtcEndpoint in loopback)
			MediaPipeline pipeline = kurento.createMediaPipeline();
			WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
			webRtcEndpoint.connect(webRtcEndpoint);

			// 2. Store user session
			UserSession user = new UserSession();
			user.setMediaPipeline(pipeline);
			user.setWebRtcEndpoint(webRtcEndpoint);
			users.put(session.getId(), user);

			// 3. SDP negotiation
			String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
			String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

			JsonObject response = new JsonObject();
			response.addProperty("id", "startResponse");
			response.addProperty("sdpAnswer", sdpAnswer);

			synchronized (session) {
				session.sendMessage(new TextMessage(response.toString()));
			}

			// 4. Gather ICE candidates
			webRtcEndpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
				@Override
				public void onEvent(OnIceCandidateEvent event) {
					JsonObject response = new JsonObject();
					response.addProperty("id", "iceCandidate");
					response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
					try {
						synchronized (session) {
							session.sendMessage(new TextMessage(response.toString()));
						}
					} catch (IOException e) {
						log.error(e.getMessage());
					}
				}
			});
			webRtcEndpoint.gatherCandidates();

		} catch (Throwable t) {
			sendError(session, t.getMessage());
		}
	}

	private void sendError(WebSocketSession session, String message) {
		try {
			JsonObject response = new JsonObject();
			response.addProperty("id", "error");
			response.addProperty("message", message);
			session.sendMessage(new TextMessage(response.toString()));
		} catch (IOException e) {
			log.error("Exception sending message", e);
		}
	}
}
