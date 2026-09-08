import { Routes } from '@angular/router';
import { Library } from './library/library';
import { DocumentView } from './document/document-view';

export const routes: Routes = [
  { path: '', component: Library, title: 'Study Assistant' },
  { path: 'documents/:id', component: DocumentView, title: 'Document · Study Assistant' },
  { path: '**', redirectTo: '' },
];
