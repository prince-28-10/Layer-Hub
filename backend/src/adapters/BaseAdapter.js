export class BaseAdapter {
  constructor(name) {
    this.name = name;
  }

  canHandle(url) {
    throw new Error('canHandle must be implemented by adapter');
  }

  async analyze(url) {
    throw new Error('analyze must be implemented by adapter');
  }

  async prepare(job, options) {
    throw new Error('prepare must be implemented by adapter');
  }
}
