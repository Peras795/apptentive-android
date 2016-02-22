/*
 * Copyright (c) 2016, Apptentive, Inc. All Rights Reserved.
 * Please refer to the LICENSE file for the terms and conditions
 * under which redistribution and use of this file is permitted.
 */

package com.apptentive.android.sdk.module.engagement.interaction.view.survey;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.apptentive.android.sdk.AboutModule;
import com.apptentive.android.sdk.ApptentiveInternal;
import com.apptentive.android.sdk.Log;
import com.apptentive.android.sdk.R;
import com.apptentive.android.sdk.ViewActivity;
import com.apptentive.android.sdk.model.SurveyResponse;
import com.apptentive.android.sdk.module.engagement.interaction.model.SurveyInteraction;
import com.apptentive.android.sdk.module.engagement.interaction.model.survey.*;
import com.apptentive.android.sdk.module.engagement.EngagementModule;
import com.apptentive.android.sdk.module.engagement.interaction.view.InteractionView;
import com.apptentive.android.sdk.module.survey.OnSurveyFinishedListener;
import com.apptentive.android.sdk.module.survey.OnSurveyQuestionAnsweredListener;
import com.apptentive.android.sdk.storage.ApptentiveDatabase;
import com.apptentive.android.sdk.util.Util;

import org.json.JSONException;
import org.json.JSONObject;


public class SurveyInteractionView extends InteractionView<SurveyInteraction> {

	private static final String EVENT_CANCEL = "cancel";
	private static final String EVENT_SUBMIT = "submit";
	private static final String EVENT_QUESTION_RESPONSE = "question_response";

	private static final String KEY_SURVEY_SUBMITTED = "survey_submitted";
	private static final String KEY_SURVEY_DATA = "survey_data";
	private boolean surveySubmitted = false;

	private SurveyState surveyState;

	public SurveyInteractionView(SurveyInteraction interaction) {
		super(interaction);
		if (surveyState == null) {
			surveyState = new SurveyState(interaction);
		}
	}

	@Override
	public void doOnCreate(final ViewActivity activity, Bundle savedInstanceState) {

		if (savedInstanceState != null) {
			surveySubmitted = savedInstanceState.getBoolean(KEY_SURVEY_SUBMITTED, false);
			surveyState = savedInstanceState.getParcelable(KEY_SURVEY_DATA);
		}

		if (interaction == null || surveySubmitted) {
			activity.finish();
			return;
		}

		activity.setContentView(R.layout.apptentive_survey);

		final Button send = (Button) activity.findViewById(R.id.send);
		send.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				Util.hideSoftKeyboard(activity, view);
				surveySubmitted = true;
				if (interaction.isShowSuccessMessage() && interaction.getSuccessMessage() != null) {
					SurveyThankYouDialog dialog = new SurveyThankYouDialog(activity);
					dialog.setMessage(interaction.getSuccessMessage());
					dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
						@Override
						public void onDismiss(DialogInterface dialogInterface) {
							activity.finish();
						}
					});
					dialog.show();
				} else {
					activity.finish();
				}

				EngagementModule.engageInternal(activity, interaction, EVENT_SUBMIT);
				ApptentiveDatabase.getInstance(activity).addPayload(new SurveyResponse(interaction, surveyState));
				Log.d("Survey Submitted.");
				callListener(true);

				cleanup();
			}
		});

		LinearLayout questions = (LinearLayout) activity.findViewById(R.id.questions);
		questions.removeAllViews();

		// Then render all the questions
		for (final Question question : interaction.getQuestions()) {
			if (question.getType() == Question.QUESTION_TYPE_SINGLELINE) {
				TextSurveyQuestionView textQuestionView = new TextSurveyQuestionView(activity, surveyState, (SinglelineQuestion) question);
				textQuestionView.setOnSurveyQuestionAnsweredListener(new OnSurveyQuestionAnsweredListener() {
					public void onAnswered() {
						sendMetricForQuestion(activity, question);
						//send.setEnabled(isSurveyValid());
					}
				});
				questions.addView(textQuestionView);
			} else if (question.getType() == Question.QUESTION_TYPE_MULTICHOICE) {
				MultichoiceSurveyQuestionView multichoiceQuestionView = new MultichoiceSurveyQuestionView(activity, surveyState, (MultichoiceQuestion) question);
				multichoiceQuestionView.setOnSurveyQuestionAnsweredListener(new OnSurveyQuestionAnsweredListener() {
					public void onAnswered() {
						sendMetricForQuestion(activity, question);
						//send.setEnabled(isSurveyValid());
					}
				});
				questions.addView(multichoiceQuestionView);
			} else if (question.getType() == Question.QUESTION_TYPE_MULTISELECT) {
				MultiselectSurveyQuestionView multiselectQuestionView = new MultiselectSurveyQuestionView(activity, surveyState, (MultiselectQuestion) question);
				multiselectQuestionView.setOnSurveyQuestionAnsweredListener(new OnSurveyQuestionAnsweredListener() {
					public void onAnswered() {
						sendMetricForQuestion(activity, question);
						//send.setEnabled(isSurveyValid());
					}
				});
				questions.addView(multiselectQuestionView);
			}
		}

		View infoButton = activity.findViewById(R.id.info);
		infoButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				AboutModule.getInstance().show(activity, false);
			}
		});
/*
		send.setEnabled(isSurveyValid());
*/
	}

	public boolean isSurveyValid() {
		for (Question question : interaction.getQuestions()) {
			if (!surveyState.isQuestionValid(question)) {
				return false;
			}
		}
		return true;
	}

	void sendMetricForQuestion(Activity activity, Question question) {
		String questionId = question.getId();
		if (!surveyState.isMetricSent(questionId) && surveyState.isQuestionValid(question)) {
			JSONObject answerData = new JSONObject();
			try {
				answerData.put("id", question.getId());
			} catch (JSONException e) {
				// Never happens.
			}
			EngagementModule.engageInternal(activity, interaction, EVENT_QUESTION_RESPONSE, answerData.toString());
			surveyState.markMetricSent(questionId);
		}
	}

	private void cleanup() {
		surveyState = null;
	}


	@Override
	public boolean onBackPressed(Activity activity) {
		// If this survey is required, do not let it be dismissed when the user clicks the back button.
		if (!interaction.isRequired()) {
			EngagementModule.engageInternal(activity, interaction, EVENT_CANCEL);
			callListener(false);
			cleanup();
			return true;
		} else {
			return false;
		}
	}

	private void callListener(boolean completed) {
		OnSurveyFinishedListener listener = ApptentiveInternal.getOnSurveyFinishedListener();
		if (listener != null) {
			listener.onSurveyFinished(completed);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(KEY_SURVEY_SUBMITTED, surveySubmitted);
		outState.putParcelable(KEY_SURVEY_DATA, surveyState);
	}

	@Override
	public void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		surveySubmitted = savedInstanceState.getBoolean(KEY_SURVEY_SUBMITTED, false);
	}
}
