import { MAX_SLOT_CAPACITY, type ActionOutcome, type JobMoveRequest, type SlotCapacityRequest } from '../../shared/actions';
import type { SlotSnapshot } from '../../shared/jobs';
import type { WorkspaceDaemon } from './WorkspaceActions';

const SLOT_NAME = /^[A-Za-z0-9._:-]{1,64}$/;
const JOB_ID = /^[A-Za-z0-9._-]{1,64}$/;

/**
 * The two actions of the Jobs screen. They are small and undoable (set the capacity back, move the job back), so there is
 * no native dialog; main still checks the request against the daemon's own slots, so the page cannot name a slot or a job
 * the daemon does not hold, or move a job that is running.
 */
export class JobActions {
  constructor(private readonly daemon: WorkspaceDaemon) {}

  /** `POST /jobs/slots/{name}`: stored in `config.json`, in force at once. */
  async setCapacity(input: unknown): Promise<ActionOutcome> {
    const req = input as Partial<SlotCapacityRequest> | null;
    const name = typeof req?.name === 'string' && SLOT_NAME.test(req.name) ? req.name : null;
    const capacity = req?.capacity;
    if (!name) return refused('Incomplete request.');
    if (typeof capacity !== 'number' || !Number.isInteger(capacity) || capacity < 1 || capacity > MAX_SLOT_CAPACITY) return refused(`Capacity must be a whole number from 1 to ${MAX_SLOT_CAPACITY}.`);

    const slot = (await this.slots()).find(s => s.name === name);
    if (!slot) return refused(`The daemon has no slot ${name}.`);
    if (slot.capacity === capacity && slot.configured === capacity) return { ok: true, message: `Slot ${name} already has capacity ${capacity}.` };
    await this.daemon.post(`/jobs/slots/${encodeURIComponent(name)}`, { capacity });
    if (capacity === slot.capacity) return { ok: true, message: `Slot ${name}: capacity ${capacity} is now written in config.json.` };
    return { ok: true, message: `Slot ${name}: capacity ${slot.capacity} → ${capacity}${capacity > slot.capacity && slot.waiting.length > 0 ? '; waiting jobs start now' : ''}.` };
  }

  /** `POST /jobs/{id}/move`: only a job that waits, and only relative to another job waiting in the same slot. */
  async move(input: unknown): Promise<ActionOutcome> {
    const req = input as Partial<JobMoveRequest> | null;
    const id = typeof req?.id === 'string' && JOB_ID.test(req.id) ? req.id : null;
    const to = req?.to;
    const target = req?.target;
    if (!id || (to !== 'first' && to !== 'before' && to !== 'after')) return refused('Incomplete request.');
    if (to === 'first' ? target !== undefined : typeof target !== 'string' || !JOB_ID.test(target) || target === id) return refused('Incomplete request.');

    const slot = (await this.slots()).find(s => s.waiting.includes(id));
    if (!slot) return refused(`${id} is not waiting in any slot (running and finished jobs do not move).`);
    if (target !== undefined && !slot.waiting.includes(target)) return refused(`${target} is not waiting in slot ${slot.name}.`);
    const body = to === 'first' ? { first: true } : to === 'before' ? { before: target } : { after: target };
    await this.daemon.post(`/jobs/${encodeURIComponent(id)}/move`, body);
    return { ok: true, message: `${id} moved ${to === 'first' ? 'to the front of' : `${to} ${target} in`} slot ${slot.name}.` };
  }

  private async slots(): Promise<SlotSnapshot[]> {
    return (await this.daemon.get<{ slots: SlotSnapshot[] }>('/jobs/slots')).slots;
  }
}

function refused(message: string): ActionOutcome {
  return { ok: false, message };
}
