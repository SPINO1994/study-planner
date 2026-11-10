'use strict';

const $ = selector => document.querySelector(selector);
const field = (form, name) => form.elements.namedItem(name);
const today = () => new Date().toISOString().slice(0, 10);
const state = {
  token: sessionStorage.getItem('planner.token'),
  email: sessionStorage.getItem('planner.email') || '',
  topics: [], plans: [], selectedPlan: null, planPage: 0, registering: false,
  manualResults: [], generatedQuizzes: [], manualPage: 0, generatedPage: 0,
  activeQuiz: null, quizMode: 'manual', aiConfigured: false
};
const requestKeys = new WeakMap();
const pendingForms = new WeakSet();

function el(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function button(text, action, id, className = 'quiet small') {
  const element = el('button', text, className);
  element.type = 'button';
  element.dataset.action = action;
  element.dataset.id = id;
  return element;
}

function notice(message = '', error = false) {
  $('#notice').textContent = message;
  $('#notice').className = error ? 'error' : '';
}

function signedOut(message = '') {
  state.token = null;
  state.email = '';
  state.topics = [];
  state.plans = [];
  state.selectedPlan = null;
  state.activeQuiz = null;
  state.manualResults = [];
  state.generatedQuizzes = [];
  sessionStorage.removeItem('planner.token');
  sessionStorage.removeItem('planner.email');
  document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
  $('#workspace').hidden = true;
  $('#account').hidden = true;
  $('#auth-view').hidden = false;
  $('#auth-error').textContent = message;
  $('#topics-list').replaceChildren();
  $('#sessions').replaceChildren();
  $('#mcp-result').replaceChildren();
  $('#quiz-questions').replaceChildren();
  $('#quiz-feedback').replaceChildren();
  $('#quiz-attempt').hidden = true;
}

async function api(path, {method = 'GET', body, timeout = 20000} = {}) {
  const headers = {Accept: 'application/json'};
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (path === '/mcp') headers.Accept = 'application/json, text/event-stream';
  let response;
  try {
    response = await fetch(path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeout), cache: 'no-store'
    });
  } catch (error) {
    throw new Error('Could not reach the server. Check the connection and try again.');
  }
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/api/auth/')) {
      signedOut('Your session has expired. Please sign in again.');
    }
    const errors = data?.errors ? Object.entries(data.errors).map(([name, message]) => `${name}: ${message}`).join('. ') : '';
    const error = new Error(errors || data?.detail || `Request failed (${response.status}).`);
    error.status = response.status;
    throw error;
  }
  return data;
}

async function rpc(method, params = {}) {
  const response = await api('/mcp', {
    method: 'POST', body: {jsonrpc: '2.0', id: crypto.randomUUID(), method, params}
  });
  if (response.error) throw new Error(response.error.message || 'MCP request failed.');
  if (response.result?.isError) {
    throw new Error(response.result.content?.find(item => item.type === 'text')?.text || 'MCP tool failed.');
  }
  return response.result;
}

function retryKey(form, payload) {
  const fingerprint = JSON.stringify(payload);
  const previous = requestKeys.get(form);
  if (previous?.fingerprint === fingerprint) return previous.id;
  const id = crypto.randomUUID();
  requestKeys.set(form, {fingerprint, id});
  return id;
}

async function submit(form, action) {
  if (pendingForms.has(form)) return;
  pendingForms.add(form);
  const errorBox = form.querySelector('[data-form-error]');
  if (errorBox) errorBox.textContent = '';
  const buttons = [...form.querySelectorAll('button')];
  buttons.forEach(item => item.disabled = true);
  try {
    await action();
  } catch (error) {
    if (errorBox) errorBox.textContent = error.message;
    else notice(error.message, true);
  } finally {
    pendingForms.delete(form);
    buttons.forEach(item => item.disabled = false);
  }
}

function showView(name) {
  for (const view of ['topics', 'quizzes', 'schedule', 'mcp']) $(`#${view}-view`).hidden = view !== name;
  document.querySelectorAll('[data-view]').forEach(item => {
    if (item.dataset.view === name) item.setAttribute('aria-current', 'page');
    else item.removeAttribute('aria-current');
  });
  notice();
  history.replaceState(null, '', `#${name}`);
}

function formatDate(value) {
  return new Intl.DateTimeFormat(undefined, {month: 'short', day: 'numeric', timeZone: 'UTC'})
    .format(new Date(`${value}T12:00:00Z`));
}

function renderTopics(progress) {
  state.topics = progress.topics;
  const weak = state.topics.filter(topic => topic.accuracy !== null && topic.accuracy < 60).length;
  $('#summary').textContent = `${progress.topicCount} topic${progress.topicCount === 1 ? '' : 's'} · ${progress.completedMinutes} minutes studied${weak ? ` · ${weak} need${weak === 1 ? 's' : ''} practice` : ''}`;
  $('#topics-empty').hidden = state.topics.length > 0;
  $('#topics-table-wrap').hidden = state.topics.length === 0;
  const rows = state.topics.map(topic => {
    const row = el('tr');
    const name = el('td');
    name.append(el('span', topic.name, 'topic-name'), el('span', `${topic.subject} · Priority ${topic.priority}`, 'topic-sub'));
    const accuracy = el('td');
    accuracy.append(el('span', topic.accuracy === null ? 'Not assessed' : `${topic.accuracy}%`,
      topic.accuracy !== null && topic.accuracy < 60 ? 'accuracy weak' : 'accuracy'));
    accuracy.append(el('span', topic.recentQuizCount ? `Latest ${topic.recentQuizCount} quiz${topic.recentQuizCount === 1 ? '' : 'zes'}` : 'Add a quiz score', 'topic-sub'));
    const time = el('td', `${topic.completedMinutes} / ${topic.estimatedMinutes} min`);
    if (topic.plannedMinutes) time.append(el('span', `${topic.plannedMinutes} min scheduled`, 'topic-sub'));
    const actions = el('td');
    const group = el('div', undefined, 'actions');
    group.append(button('Add score', 'quiz', topic.topicId, 'secondary small'), button('Edit', 'edit', topic.topicId));
    actions.append(group);
    row.append(name, accuracy, time, el('td', topic.examDate ? formatDate(topic.examDate) : '—'), actions);
    return row;
  });
  $('#topics-list').replaceChildren(...rows);
  updateQuizTopicOptions();
}

function renderPlanPicker() {
  const selected = state.selectedPlan?.id;
  const options = state.plans.map(plan => {
    const option = el('option', `${formatDate(plan.startDate)} · ${plan.days} day${plan.days === 1 ? '' : 's'} · ${plan.dailyMinutes} min/day`);
    option.value = plan.id;
    return option;
  });
  if (!options.length) {
    const option = el('option', 'No saved plans yet');
    option.value = '';
    options.push(option);
  }
  $('#plan-select').replaceChildren(...options);
  if (state.plans.some(plan => plan.id === selected)) $('#plan-select').value = selected;
}

function renderPlan(plan) {
  state.selectedPlan = plan;
  $('#plan-empty').hidden = !!plan;
  $('#plan-detail').hidden = !plan;
  $('#sessions').replaceChildren();
  if (!plan) return;
  $('#plan-description').textContent = `${plan.days} day${plan.days === 1 ? '' : 's'} · ${plan.dailyMinutes} minutes per day${plan.unallocatedMinutes ? ` · ${plan.unallocatedMinutes} minutes left unallocated` : ''}`;
  $('#cancel-plan').hidden = !plan.sessions.some(session => session.status === 'PLANNED');
  const days = Map.groupBy(plan.sessions, session => session.studyDate);
  for (const [date, sessions] of days) {
    const section = el('section', undefined, 'day');
    const total = sessions.reduce((sum, session) => sum + session.minutes, 0);
    section.append(el('h2', `${date === today() ? 'Today' : formatDate(date)} · ${total} min`));
    for (const session of sessions) {
      const row = el('div', undefined, 'session');
      const title = el('div', session.topicName);
      title.append(el('span', `${session.minutes} min`, 'minutes'));
      row.append(title);
      if (session.status === 'PLANNED' && session.studyDate <= today()) {
        row.append(button('Mark done', 'complete', session.id, 'secondary small'));
      } else {
        const label = session.status === 'COMPLETED' ? 'Completed' : session.status === 'SKIPPED' ? 'Skipped' : 'Upcoming';
        row.append(el('span', label, `status${session.status === 'COMPLETED' ? ' done' : ''}`));
      }
      section.append(row);
    }
    $('#sessions').append(section);
  }
}

async function refresh(preferredPlanId) {
  const [progress, plans] = await Promise.all([api('/api/progress'), api('/api/plans')]);
  renderTopics(progress);
  state.plans = plans;
  state.planPage = 0;
  $('#more-plans').hidden = plans.length < 20;
  renderPlanPicker();
  const id = preferredPlanId || state.selectedPlan?.id || plans[0]?.id;
  if (id) {
    const plan = await api(`/api/plans/${id}`);
    if (!state.plans.some(item => item.id === id)) state.plans.unshift(plan);
    renderPlan(plan);
    renderPlanPicker();
    $('#plan-select').value = id;
  } else renderPlan(null);
}

async function enterWorkspace() {
  $('#auth-view').hidden = true;
  $('#workspace').hidden = false;
  $('#account').hidden = false;
  $('#account-email').textContent = state.email;
  const requestedView = location.hash.slice(1);
  showView(['topics', 'quizzes', 'schedule', 'mcp'].includes(requestedView) ? requestedView : 'topics');
  try { await Promise.all([refresh(), loadQuizHistory()]); } catch (error) { notice(error.message, true); }
}

function openTopic(topic) {
  const form = $('#topic-form');
  form.reset();
  form.querySelector('[data-form-error]').textContent = '';
  $('#topic-dialog-title').textContent = topic ? 'Edit topic' : 'Add topic';
  field(form, 'id').value = topic?.topicId || '';
  for (const key of ['subject', 'name', 'priority', 'estimatedMinutes', 'examDate']) {
    if (topic) field(form, key).value = topic[key] ?? '';
  }
  field(form, 'examDate').min = today();
  $('#topic-dialog').showModal();
}

$('#auth-toggle').addEventListener('click', () => {
  state.registering = !state.registering;
  $('#auth-title').textContent = state.registering ? 'Create account' : 'Sign in';
  $('#auth-submit').textContent = state.registering ? 'Create account' : 'Sign in';
  $('#auth-toggle').textContent = state.registering ? 'Already have an account? Sign in' : 'New here? Create an account';
  field($('#auth-form'), 'password').autocomplete = state.registering ? 'new-password' : 'current-password';
  $('#auth-error').textContent = '';
});

$('#auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const buttons = [...form.querySelectorAll('button')];
  buttons.forEach(item => item.disabled = true);
  $('#auth-error').textContent = '';
  try {
    const email = field(form, 'email').value.trim();
    const result = await api(`/api/auth/${state.registering ? 'register' : 'login'}`, {
      method: 'POST', body: {email, password: field(form, 'password').value}
    });
    state.token = result.accessToken;
    state.email = email;
    sessionStorage.setItem('planner.token', state.token);
    sessionStorage.setItem('planner.email', email);
    field(form, 'password').value = '';
    await enterWorkspace();
  } catch (error) { $('#auth-error').textContent = error.message; }
  finally { buttons.forEach(item => item.disabled = false); }
});

$('#logout').addEventListener('click', async () => {
  $('#logout').disabled = true;
  try {
    await api('/api/auth/logout', {method: 'POST'});
    signedOut();
  } catch (error) { notice(error.message, true); }
  finally { $('#logout').disabled = false; }
});

document.querySelectorAll('[data-view]').forEach(item => item.addEventListener('click', () => showView(item.dataset.view)));
document.querySelectorAll('[data-close]').forEach(item => item.addEventListener('click', () => $(`#${item.dataset.close}`).close()));
$('#add-topic').addEventListener('click', () => openTopic());
$('#add-first-topic').addEventListener('click', () => openTopic());
$('#refresh').addEventListener('click', async () => {
  $('#refresh').disabled = true;
  try { await Promise.all([refresh(), loadQuizHistory()]); notice('Progress updated.'); }
  catch (error) { notice(error.message, true); }
  finally { $('#refresh').disabled = false; }
});

$('#topics-list').addEventListener('click', event => {
  const target = event.target.closest('button[data-action]');
  if (!target) return;
  const topic = state.topics.find(item => item.topicId === target.dataset.id);
  if (!topic) return;
  if (target.dataset.action === 'edit') return openTopic(topic);
  const form = $('#quiz-form');
  form.reset();
  form.querySelector('[data-form-error]').textContent = '';
  field(form, 'topicId').value = topic.topicId;
  $('#quiz-topic').textContent = `${topic.subject} / ${topic.name}`;
  field(form, 'correctAnswers').max = field(form, 'totalQuestions').value;
  $('#quiz-dialog').showModal();
});

$('#topic-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  submit(form, async () => {
    const id = field(form, 'id').value;
    const body = {
      subject: field(form, 'subject').value.trim(), name: field(form, 'name').value.trim(),
      priority: Number(field(form, 'priority').value), estimatedMinutes: Number(field(form, 'estimatedMinutes').value),
      examDate: field(form, 'examDate').value || null
    };
    await api(`/api/topics${id ? `/${id}` : ''}`, {method: id ? 'PUT' : 'POST', body});
    $('#topic-dialog').close();
    await refresh();
    notice(id ? 'Topic updated.' : 'Topic added.');
  });
});

field($('#quiz-form'), 'totalQuestions').addEventListener('input', event => {
  field($('#quiz-form'), 'correctAnswers').max = event.target.value;
});

$('#quiz-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  submit(form, async () => {
    const topicId = field(form, 'topicId').value;
    const body = {correctAnswers: Number(field(form, 'correctAnswers').value), totalQuestions: Number(field(form, 'totalQuestions').value), title: field(form, 'title').value.trim() || null};
    const requestId = retryKey(form, {topicId, ...body});
    await api(`/api/topics/${topicId}/quiz-results`, {method: 'POST', body: {...body, requestId}});
    requestKeys.delete(form);
    $('#quiz-dialog').close();
    await refresh();
    await loadQuizHistory();
    notice('Quiz result saved.');
  });
});

$('#plan-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  submit(form, async () => {
    const body = {startDate: field(form, 'startDate').value, days: Number(field(form, 'days').value), dailyMinutes: Number(field(form, 'dailyMinutes').value)};
    const requestId = retryKey(form, body);
    const plan = await api('/api/plans', {method: 'POST', body: {...body, requestId}});
    requestKeys.delete(form);
    await refresh(plan.id);
    notice('Study plan saved.');
  });
});

$('#plan-select').addEventListener('change', async event => {
  const id = event.target.value;
  if (!id) return;
  try {
    const plan = await api(`/api/plans/${id}`);
    if ($('#plan-select').value === id) renderPlan(plan);
  } catch (error) { notice(error.message, true); }
});

$('#more-plans').addEventListener('click', async () => {
  $('#more-plans').disabled = true;
  try {
    const page = state.planPage + 1;
    const plans = await api(`/api/plans?page=${page}`);
    state.planPage = page;
    state.plans.push(...plans.filter(plan => !state.plans.some(item => item.id === plan.id)));
    renderPlanPicker();
    $('#more-plans').hidden = plans.length < 20;
  } catch (error) { notice(error.message, true); }
  finally { $('#more-plans').disabled = false; }
});

$('#sessions').addEventListener('click', async event => {
  const target = event.target.closest('button[data-action="complete"]');
  if (!target || target.disabled) return;
  target.disabled = true;
  try {
    await api(`/api/sessions/${target.dataset.id}/complete`, {method: 'POST'});
    await refresh();
    notice('Session completed.');
  } catch (error) { notice(error.message, true); target.disabled = false; }
});

$('#cancel-plan').addEventListener('click', async () => {
  if (!state.selectedPlan || !confirm('Cancel the remaining sessions? Completed study time will be kept.')) return;
  $('#cancel-plan').disabled = true;
  try {
    await api(`/api/plans/${state.selectedPlan.id}/cancel`, {method: 'POST'});
    await refresh();
    notice('Remaining sessions cancelled.');
  } catch (error) { notice(error.message, true); }
  finally { $('#cancel-plan').disabled = false; }
});

$('#check-mcp').addEventListener('click', async () => {
  $('#check-mcp').disabled = true;
  $('#mcp-result').replaceChildren(el('p', 'Checking the connection…', 'muted'));
  try {
    const initialized = await rpc('initialize', {protocolVersion: '2025-06-18', capabilities: {}, clientInfo: {name: 'study-planner-ui', version: '0.1.0'}});
    const tools = await rpc('tools/list');
    const progress = await rpc('tools/call', {name: 'get_progress', arguments: {}});
    const data = progress.structuredContent?.data;
    if (!initialized.serverInfo?.name || !Array.isArray(tools.tools) || typeof data?.topicCount !== 'number') {
      throw new Error('The server returned an unexpected MCP response.');
    }
    const list = el('ul');
    tools.tools.forEach(tool => list.append(el('li', tool.name)));
    $('#mcp-result').replaceChildren(
      el('h2', 'Connection working', 'success'),
      el('p', `${initialized.serverInfo.name} · ${tools.tools.length} tools available · ${data.topicCount} topic${data.topicCount === 1 ? '' : 's'} read through get_progress`),
      list
    );
  } catch (error) {
    $('#mcp-result').replaceChildren(el('p', error.message, 'error'));
  } finally { $('#check-mcp').disabled = false; }
});

function updateQuizTopicOptions() {
  for (const formId of ['manual-result-form', 'generate-quiz-form']) {
    const form = $(`#${formId}`);
    const select = field(form, 'topicId');
    const previous = select.value;
    const options = state.topics.map(topic => {
      const option = el('option', `${topic.subject} / ${topic.name}`);
      option.value = topic.topicId;
      return option;
    });
    if (!options.length) {
      const option = el('option', 'Add a topic first');
      option.value = '';
      options.push(option);
    }
    select.replaceChildren(...options);
    if (state.topics.some(topic => topic.topicId === previous)) select.value = previous;
    if (!pendingForms.has(form)) {
      form.querySelector('button[type="submit"]').disabled = !state.topics.length || (formId === 'generate-quiz-form' && !state.aiConfigured);
    }
  }
}

async function loadQuizHistory() {
  const [manual, generated, status] = await Promise.all([
    api('/api/quizzes/manual'), api('/api/quizzes/generated'), api('/api/quizzes/ai-status')
  ]);
  state.manualResults = manual;
  state.generatedQuizzes = generated;
  state.manualPage = 0;
  state.generatedPage = 0;
  state.aiConfigured = status.configured;
  $('#ai-status').textContent = status.configured ? `Server key configured · ${status.model}` : 'AI generation is not configured. Add OPENAI_API_KEY to the server’s .env file and restart. You can still save manual results.';
  $('#ai-status').className = status.configured ? 'hint success' : 'hint';
  $('#more-manual').hidden = manual.length < 20;
  $('#more-generated').hidden = generated.length < 20;
  renderQuizHistory();
  updateQuizTopicOptions();
}

function renderQuizHistory() {
  const manual = state.manualResults.map(result => {
    const row = el('div', undefined, 'history-item');
    const details = el('div');
    details.append(el('strong', result.title), el('p', `${result.topicName} · ${formatDate(result.attemptedAt.slice(0, 10))}`));
    if (result.notes) details.append(el('p', result.notes));
    const score = Math.round(10000 * result.correctAnswers / result.totalQuestions) / 100;
    row.append(details, el('span', `${result.correctAnswers}/${result.totalQuestions} · ${score}%`, 'history-score'));
    return row;
  });
  $('#manual-history').replaceChildren(...(manual.length ? manual : [el('p', 'No manual results yet.', 'muted')]));
  const generated = state.generatedQuizzes.map(quiz => {
    const row = el('div', undefined, 'history-item');
    const details = el('div');
    details.append(el('strong', quiz.topicName), el('p', `${quiz.questionCount} questions · ${quiz.difficulty.toLowerCase()} · ${formatDate(quiz.createdAt.slice(0, 10))}`));
    row.append(details);
    if (quiz.status === 'READY' || quiz.status === 'SUBMITTED') {
      row.append(button(quiz.status === 'SUBMITTED' ? 'Review result' : 'Take quiz', 'open-quiz', quiz.id, 'secondary small'));
    } else row.append(el('span', quiz.status === 'GENERATING' ? 'Generating…' : 'Generation failed', 'hint'));
    return row;
  });
  $('#generated-history').replaceChildren(...(generated.length ? generated : [el('p', 'No generated quizzes yet.', 'muted')]));
}

function showQuizMode(mode) {
  state.quizMode = mode;
  $('#manual-quizzes').hidden = mode !== 'manual';
  $('#ai-quizzes').hidden = mode !== 'ai';
  document.querySelectorAll('[data-quiz-mode]').forEach(item => item.setAttribute('aria-pressed', String(item.dataset.quizMode === mode)));
}

function displayQuiz(quiz) {
  state.activeQuiz = quiz;
  $('#quiz-attempt').hidden = false;
  $('#attempt-title').textContent = quiz.topicName;
  $('#attempt-description').textContent = `${quiz.questionCount} questions · ${quiz.difficulty.toLowerCase()}`;
  $('#attempt-form').hidden = quiz.status !== 'READY';
  $('#quiz-feedback').hidden = quiz.status !== 'SUBMITTED';
  $('#attempt-form').querySelector('[data-form-error]').textContent = '';
  $('#quiz-questions').replaceChildren();
  $('#quiz-feedback').replaceChildren();
  if (quiz.status === 'READY') {
    const questions = quiz.questions.map(question => {
      const group = el('fieldset', undefined, 'question');
      group.append(el('legend', `${question.number}. ${question.prompt}`));
      question.options.forEach((option, index) => {
        const label = el('label', undefined, 'question-option');
        const input = document.createElement('input');
        input.type = 'radio';
        input.name = `question-${question.number}`;
        input.value = String(index);
        input.required = true;
        label.append(input, el('span', `${String.fromCharCode(65 + index)}. ${option}`));
        group.append(label);
      });
      return group;
    });
    $('#quiz-questions').replaceChildren(...questions);
  } else if (quiz.feedback) renderQuizFeedback(quiz.feedback);
}

function renderQuizFeedback(feedback) {
  const summary = el('div', undefined, 'quiz-feedback-summary');
  summary.append(el('h2', `${feedback.correctAnswers} / ${feedback.totalQuestions} correct · ${feedback.accuracy}%`));
  summary.append(el('p', 'Your score has been added to this topic’s study progress.', 'muted'));
  summary.append(el('h3', feedback.weakPoints.length ? 'Areas to review' : 'No missed areas in this attempt'));
  for (const point of feedback.weakPoints) {
    const group = el('div', undefined, 'weak-point');
    group.append(el('strong', point.subtopic), el('p', `${point.correctAnswers}/${point.totalQuestions} correct · ${point.accuracy}% — review this area before your next attempt.`));
    summary.append(group);
  }
  summary.append(el('p', 'This report groups missed answers by subtopic. A short quiz is a practice signal, not a complete assessment.', 'hint'));
  $('#quiz-feedback').append(summary, el('h2', 'Answer review'));
  for (const item of feedback.review) {
    const correct = item.selectedOption === item.correctOption;
    const group = el('div', undefined, `answer-review${correct ? '' : ' missed'}`);
    group.append(el('strong', `${item.number}. ${item.prompt}`));
    group.append(el('p', `Your answer: ${String.fromCharCode(65 + item.selectedOption)}. ${item.options[item.selectedOption]}${correct ? ' — Correct' : ''}`));
    if (!correct) group.append(el('p', `Correct answer: ${String.fromCharCode(65 + item.correctOption)}. ${item.options[item.correctOption]}`));
    group.append(el('p', `${item.subtopic}: ${item.explanation}`, 'explanation'));
    $('#quiz-feedback').append(group);
  }
}

document.querySelectorAll('[data-quiz-mode]').forEach(item => item.addEventListener('click', () => showQuizMode(item.dataset.quizMode)));
field($('#manual-result-form'), 'totalQuestions').addEventListener('input', event => {
  field($('#manual-result-form'), 'correctAnswers').max = event.target.value;
});
field($('#manual-result-form'), 'correctAnswers').max = '10';

$('#manual-result-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  submit(form, async () => {
    const topicId = field(form, 'topicId').value;
    const body = {title: field(form, 'title').value.trim(), notes: field(form, 'notes').value.trim(),
      correctAnswers: Number(field(form, 'correctAnswers').value), totalQuestions: Number(field(form, 'totalQuestions').value)};
    const requestId = retryKey(form, {topicId, ...body});
    await api(`/api/topics/${topicId}/quiz-results`, {method: 'POST', body: {...body, requestId}});
    requestKeys.delete(form);
    form.reset();
    field(form, 'correctAnswers').max = '10';
    await Promise.all([refresh(), loadQuizHistory()]);
    notice('Manual result saved.');
  });
});

$('#generate-quiz-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  submit(form, async () => {
    const body = {topicId: field(form, 'topicId').value, questionCount: Number(field(form, 'questionCount').value), difficulty: field(form, 'difficulty').value};
    const requestId = retryKey(form, body);
    $('#generation-progress').textContent = 'Writing your questions…';
    try {
      const quiz = await api('/api/quizzes/generated', {method: 'POST', body: {...body, requestId}, timeout: 120000});
      requestKeys.delete(form);
      displayQuiz(quiz);
      await loadQuizHistory();
      $('#quiz-attempt').scrollIntoView({behavior: 'smooth', block: 'start'});
    } catch (error) {
      if (error.status && error.status !== 409) requestKeys.delete(form);
      await loadQuizHistory().catch(() => {});
      throw error;
    } finally { $('#generation-progress').textContent = ''; }
  });
});

$('#attempt-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget;
  if (!state.activeQuiz) return;
  submit(form, async () => {
    const data = new FormData(form);
    const answers = state.activeQuiz.questions.map(question => {
      const value = data.get(`question-${question.number}`);
      if (value === null) throw new Error('Answer every question before submitting.');
      return Number(value);
    });
    const quiz = await api(`/api/quizzes/generated/${state.activeQuiz.id}/submit`, {method: 'POST', body: {answers}});
    displayQuiz(quiz);
    await Promise.all([refresh(), loadQuizHistory()]);
    $('#quiz-attempt').scrollIntoView({behavior: 'smooth', block: 'start'});
    notice('Quiz submitted. Your weak areas and answer review are below.');
  });
});

$('#generated-history').addEventListener('click', async event => {
  const target = event.target.closest('button[data-action="open-quiz"]');
  if (!target || target.disabled) return;
  target.disabled = true;
  try {
    const quiz = await api(`/api/quizzes/generated/${target.dataset.id}`);
    displayQuiz(quiz);
    $('#quiz-attempt').scrollIntoView({behavior: 'smooth', block: 'start'});
  } catch (error) { notice(error.message, true); }
  finally { target.disabled = false; }
});

$('#hide-attempt').addEventListener('click', () => {
  $('#quiz-attempt').hidden = true;
  state.activeQuiz = null;
});

for (const [buttonId, type] of [['more-manual', 'manual'], ['more-generated', 'generated']]) {
  $(`#${buttonId}`).addEventListener('click', async () => {
    const button = $(`#${buttonId}`);
    button.disabled = true;
    try {
      const page = type === 'manual' ? state.manualPage + 1 : state.generatedPage + 1;
      const results = await api(`/api/quizzes/${type}?page=${page}`);
      if (type === 'manual') { state.manualPage = page; state.manualResults.push(...results); }
      else { state.generatedPage = page; state.generatedQuizzes.push(...results); }
      button.hidden = results.length < 20;
      renderQuizHistory();
    } catch (error) { notice(error.message, true); }
    finally { button.disabled = false; }
  });
}

const startInput = field($('#plan-form'), 'startDate');
startInput.value = today();
startInput.min = today();
const maxDate = new Date();
maxDate.setUTCDate(maxDate.getUTCDate() + 90);
startInput.max = maxDate.toISOString().slice(0, 10);
$('#mcp-url').textContent = `${location.origin}/mcp`;
if (state.token) enterWorkspace();
