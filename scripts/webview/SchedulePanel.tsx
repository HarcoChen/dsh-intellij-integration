import React from 'react';
import type { ChatViewState, DshScheduleItem } from '../../../../src/types';
import { postAction } from '../../bridge';
import { t } from '../../i18n';

// Editing/history use IntelliJ's native management dialog; the dock remains a read-only summary.
function timing(item: DshScheduleItem): string {
    if (item.kind === 'after') return t('After {duration}', { duration: `${item.afterSeconds}s` });
    if (item.kind === 'every') return t('Every {duration}', { duration: `${item.everySeconds}s` });
    if (item.kind === 'daily') return t('Daily at {time} ({timeZone})', { time: item.time, timeZone: item.timeZone });
    if (item.kind === 'weekly') return t('Weekly on {days} at {time} ({timeZone})', { days: item.weekdays.join(', '), time: item.time, timeZone: item.timeZone });
    if (item.kind === 'cron') return t('Cron {expression} ({timeZone})', { expression: item.expression, timeZone: item.timeZone });
    return t('One-time');
}

export function SchedulePanel({ schedule, catalog }: { schedule?: DshScheduleItem[]; catalog?: ChatViewState['scheduleCatalog'] }): React.JSX.Element {
    return <div className="dsh-schedule" aria-label={t('Active reminders')}>
        <button type="button" className="dsh-button dsh-button-secondary" onClick={() => postAction({ type: 'manageSchedules' })}>{t('Manage schedules')}</button>
        {catalog?.status === 'unavailable' ? <div className="dsh-card-detail">{t('Enable the Schedule bundle in plugin settings to use reminders.')}</div> : null}
        <ul className="dsh-schedule-items">
            {(schedule ?? []).map(item => <li className="dsh-schedule-item" key={item.id}>
                <div className="dsh-schedule-prompt">{item.prompt}</div>
                <div className="dsh-schedule-meta">{timing(item)} · {t('Next at {time}', { time: new Date(item.scheduledAt).toLocaleString() })}</div>
                <div className="dsh-schedule-id">{t('ID {id}', { id: item.id })}</div>
            </li>)}
        </ul>
    </div>;
}
